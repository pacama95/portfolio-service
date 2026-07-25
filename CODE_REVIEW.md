# Code Review — Findings & Action Plan

Full review of portfolio-service (2026-07-19). Scope: domain fold (`LedgerReplay`), use-case
services, market-data read-through layer, persistence adapters, REST layer, schema, config.

Overall: the transactions+portfolio merge is the right design — ledger as single source of
truth, derived reads, store-first market data. The issues below are bugs and gaps inside that
design, not reasons to change it.

Severity legend: 🔴 Critical (wrong/corrupted data or broken endpoints) · 🟠 Significant ·
🟡 Minor / cleanup.

## Status (as of 2026-07-26)

**Done:** Phase 1 (C2, C3), Phase 2 (C1, S1), Phase 3 core (C4) plus a concurrency hardening
pass that went beyond the original plan, Phase 4 (C5, S6, M8, M6) including an M8 fix beyond
the original plan's numbered steps, Phase 5 (S2, S3, S4, S5), and Phase 6 (S7, M1, M2, M4, M5,
M7, M3) — see phase notes below for what shipped vs. what changed from the original design.

**Deferred, not started:** S8 (split out of Phase 3 — own turn, touches `Transaction`
constructor at ~17 call sites + schema migration).

S8 remains the only open item — pick it up if the same-day-ordering edge case becomes a live
concern.

---

## Findings

### ✅ 🔴 C1 — Valuation history never converts currencies; FX pipeline is dead code — DONE (Phase 2)

**Where:** `GetPortfolioValuationHistoryService.buildValuations`
(`src/main/java/com/portfolio/core/application/usecase/history/GetPortfolioValuationHistoryService.java:92-126`)

Sums `shares × price` across all tickers and labels the result `baseCurrency`, but each price
is in the provider's quote currency and each cost basis is in the transaction currency. A
EUR+USD portfolio produces a "USD" series that is actually a raw EUR+USD sum.

- `GetPortfolioSummaryService` **does** convert (via `getFxRate`), so `/portfolio/summary` and
  `/daily-history/portfolio/valuation` disagree for any non-USD holding.
- `MarketDataPort.getFxHistory`, `fx_rate_history` table, and the FX time-series provider code
  are fully built but **never called**.
- `/performance-inputs` inherits the bug and additionally mixes (mislabeled) base-currency
  valuations with native-currency capital flows in one payload — any TWR/MWR computed from it
  is wrong.

### ✅ 🔴 C2 — Provider error responses poison the coverage table permanently — DONE (Phase 1)

**Where:** `TwelveDataProviderAdapter.warnIfProviderError` + `MarketDataService.getPriceHistory`
(`src/main/java/com/portfolio/core/application/service/MarketDataService.java:137-141`)

TwelveData returns `{"status":"error"}` with HTTP 200 (e.g. rate limit — free tier is 8
credits/min, which the per-symbol sequential loops will exceed for any portfolio with >8
tickers). The adapter only **logs a warning**, parsing yields an empty list, and then
`recordCoverage(...)` marks the whole requested range as covered anyway. Every later request
for that symbol/range short-circuits on `hasCoverage` → a **permanent hole in the price
history that never self-heals**. Same pattern in `getFxHistory`.

### ✅ 🔴 C3 — Coverage is recorded for dates that have no data yet — DONE (Phase 1)

**Where:** `MarketDataService.getPriceHistory` / `getFxHistory` (`recordCoverage` calls)

Coverage stores the *requested* `[from, to]`, not the range actually returned. Requesting
`to = today` before the close — or any future `to`, which nothing rejects — records coverage
over dates with no data. Example: a valuation query `from=2026-01-01&to=2026-12-31` made in
July means that symbol is never fetched again for the rest of the year.

### ✅ 🔴 C4 — Oversold ledger: 500s on positions/summary, silent corruption on history — DONE (Phase 3)

**Where:** `CreateTransactionService`, `UpdateTransactionService`, `LedgerReplay.replayAll`
(`LedgerReplay.java:93`), `LedgerReplay.dailySnapshots` (`LedgerReplay.java:132-134`)

No write-time position validation: a SELL exceeding holdings is accepted, and an update can
retroactively create an oversell (shrink an earlier BUY, move a date, flip BUY→SELL). The two
read paths then disagree violently:

- `replayAll` throws `IllegalStateException` → `/api/positions` and `/api/portfolio/summary`
  become unexplained 500s for that user until the row is fixed by hand in the DB.
- `dailySnapshots` silently `continue`s past the bad transaction and keeps folding later ones
  → history endpoints return numbers inconsistent with positions, with no error flag.

**Resolution note (deviated from the original 3-step plan below):** shipped write-time
prevention (create/update/delete reject an oversell as `Result.Conflict`) plus two things the
original plan didn't call for:

- **Concurrency correctness.** The write-time check alone was TOCTOU-vulnerable (read + replay
  + write across separate reactive transactions). Closed via
  `TransactionRepository.withTickersLocked`, a Postgres advisory lock
  (`pg_advisory_xact_lock`, keyed by `userId+ticker`, sorted-order acquisition to avoid
  deadlocks) held for the whole read-validate-write span inside one `@WithTransaction`. Proven
  with a real two-thread HTTP integration test (`TransactionApiIT`) — exactly one of two
  concurrent oversell-racing SELLs succeeds.
- **No read-side "graceful degradation" layer** (the sealed `ReplayResult.Inconsistent` +
  `inconsistentTickers` field described in the original step 2, below) — deliberately dropped
  per direction to prevent inconsistencies at the write boundary rather than build read-side
  reporting for them. Instead, `LedgerReplay` now throws a single dedicated
  `LedgerInconsistencyException` (replacing bare `IllegalStateException`/`IllegalArgumentException`),
  caught precisely at each read-side service and converted to `Result.Conflict` → REST 409
  (defense-in-depth for any inconsistency that predates the write-time fix or bypasses it).

S8 (same-day determinism, step 3 of the original plan) was **not** done — split out as its own
deferred item, see below.

### ✅ 🔴 C5 — A crashed ingestion run blocks all ingestion forever — DONE (Phase 4, step 1)

**Where:** `PriceIngestionRunRepositoryAdapter.hasActiveRun`,
`TriggerPriceIngestionService.startAsync`

`hasActiveRun` counted any PENDING/RUNNING row with no age cutoff, and the run itself is
fire-and-forget. If the process died mid-run the row stayed RUNNING forever, and every later
trigger — **including the daily 06:00 cron** — returned `Conflict` until manual DB surgery.

**Resolution:** `hasActiveRun` now takes a `staleAfter` duration, reaps any PENDING/RUNNING row
whose `coalesce(started_at, created_at)` predates `now() - staleAfter` to FAILED
("Ingestion run timed out") before checking for a still-active run — closing the window where
a PENDING row with no `started_at` yet would otherwise be immortal too. Threshold is
configurable via `application.market-data.ingestion.stale-run-timeout` (default `PT3H`), same
`Duration`-config-property pattern as `spot-price-freshness`/`fx-freshness` in
`MarketDataService`. Tests: unit test for `TriggerPriceIngestionService` (mocked repo);
integration test against Testcontainers Postgres proving a stale RUNNING row is reaped to
FAILED and a fresh PENDING row is left alone.

### ✅ 🟠 S1 — Wrong currencies stored for most non-US exchanges (LSE off by 100×) — DONE (Phase 2)

**Where:** `Currency` enum, `TwelveDataProviderAdapter.parsePriceSeries:112-120`, `exchanges`
table (`schema.sql:133-168`)

`Currency` only has USD/EUR/GBP/CAD/JPY; unknown provider currencies default **to USD with a
warning**. The `exchanges` seed itself lists CHF, AUD, HKD, SEK, NOK — none representable.
Worse: LSE quotes come back as `GBp` (pence) → stored as "USD" at 100× the true GBP value.
The `exchanges.price_multiplier` column (0.01 for GBp) is the intended fix, but **no code ever
queries the `exchanges` table**.

**Resolution note:** GBp/GBX resolution deliberately kept *out* of `TwelveDataProviderAdapter`
(a provider-specific adapter shouldn't own a general market convention) and extracted to a
configurable `CurrencyResolver` in `core/application/service`, driven by config properties —
per explicit direction mid-Phase-2.

### ✅ 🟠 S2 — `dailySnapshots` emits multiple rows per (ticker, day) — DONE (Phase 5)

**Where:** `LedgerReplay.dailySnapshots`

Javadoc says "one row per ticker per day" but it emits one row per *transaction*. The valuation
service dedups via its `toMap` merge, but `/api/daily-history/positions` returns duplicates,
including misleading intraday partial states (a same-day buy+sell shows both intermediate
positions).

**Resolution:** the per-transaction `snapshots.add(...)` now upserts into a per-ticker
`TreeMap<LocalDate, DailyPositionSnapshot>` keyed by transaction date, overwriting any existing
entry for that date, then flattens into the outer list — `chronological()`'s fixed same-day
tie-break guarantees the last write for a date is the correct end-of-day state. Test:
same-day BUY+SELL now produces exactly one row reflecting the post-SELL state.

### ✅ 🟠 S3 — Valuation price lookup can't see before `from` — DONE (Phase 5)

**Where:** `GetPortfolioValuationHistoryService.buildValuations:81`

Prices are fetched for `[from, to]`, but the first days of the range need a price floor from
*before* `from` (range starting on a weekend/holiday → `complete=false` spuriously).

**Resolution:** `loadPrices` now fetches from `from.minusDays(PRICE_STALENESS_DAYS)` (mirroring
the FX lookback pattern `loadFxRates` already used), giving `TimeSeriesLookup.floorWithinStaleness`
a usable floor for the first days of the range.

### ✅ 🟠 S4 — Cost basis fluctuates with market-data gaps — DONE (Phase 5)

**Where:** `GetPortfolioValuationHistoryService.buildValuations:109-111`

When a ticker's price is stale (>5d), the `continue` also skips adding its
`totalInvestedAmount`. Cost is a pure ledger number and must not depend on price availability;
only marketValue should.

**Resolution:** `buildDailyValuations` reordered so cost basis is accumulated as soon as the FX
rate is known (cost only needs FX, not price); the price lookup and market-value accumulation
happen after, gated independently. A missing FX rate still blocks both (cost genuinely can't be
converted to base currency without one); a missing/stale price now only blocks market value.

### ✅ 🟠 S5 — Missing spot price reads as a −100% loss — DONE (Phase 5)

**Where:** `GetPortfolioSummaryService.enrichAndSummarize` (recover with `null`),
`Position.marketValue` (null → `ZERO`)

A failed spot fetch silently yields marketValue 0 → `unrealizedGainLoss = -invested`. Summary
has no `complete`/`priced` flag (valuations do).

**Resolution:** added `boolean complete` to `PortfolioSummary` (and `PortfolioSummaryResponse`,
auto-mapped by MapStruct), mirroring `DailyValuation.complete`. `enrichAndSummarize` now tracks
whether any spot-price fetch failed and threads it into the final summary. Kept minimal per the
review's fallback option: positions are still counted and cost still contributes even when
unpriced — only the new flag signals the numbers are unreliable, matching how `DailyValuation`
already surfaces the same signal.

### ✅ 🟠 S6 — Ingestion run aborts on first symbol failure — DONE (Phase 4)

**Where:** `TriggerPriceIngestionService.ingestSymbols`

One bad symbol failed the whole chained Uni; remaining symbols were skipped, run marked FAILED
with no record of which symbols succeeded.

**Resolution:** each symbol fetch is wrapped in `.onFailure().recoverWithItem(...)`; a failure
is logged and appended to a per-run failure list instead of aborting the chain, and the loop
continues to the next symbol. `symbolsCompleted` only increments for successes. At the end,
the run is marked `COMPLETED` if nothing failed, or the new `PARTIAL` status (added to
`IngestionRun.Status` and the `ingestion_run_status` Postgres enum via changeset
`003-extend-ingestion-run-status`) with `errorMessage` summarizing which symbols failed and
why. Also added, same step: a fixed delay (`application.market-data.ingestion.symbol-fetch-delay`,
default `PT8S`) between symbol fetches within one run, sized to stay under TwelveData's free-tier
8-credits/min limit (the Phase 4 action-plan's "throttle provider calls" item). Tests: one
symbol failing leaves the rest processed and the run `PARTIAL` with an accurate
`symbolsCompleted` count and a failure-listing `errorMessage`.

### ✅ 🟠 S7 — Row-by-row upserts (select + merge per row, sequential) — DONE (Phase 6)

**Where:** `MarketDataStoreAdapter.upsertPrices` / `upsertFxRates`

A 5-year backfill for one symbol ≈ 2,500 sequential round trips inside one transaction. Use
batched native `INSERT … ON CONFLICT (symbol, price_date) DO UPDATE`. Related: the valuation
service issues one `findPrices` query per ticker sequentially — could be one
`symbol IN (:symbols)` query.

**Resolution:** both methods replaced with a single native multi-row `INSERT ... VALUES
(...),(...),... ON CONFLICT (...) DO UPDATE` (following the `recordCoverage` native-SQL
precedent) — one round trip per call regardless of batch size. Postgres cast gotcha found and
fixed along the way: `:param::currency_type` right after a named parameter makes Hibernate's
parser swallow the `::cast` into the parameter name (`UnknownParameterException`); wrapping the
parameter in parens (`(:param)::currency_type`) fixes it. Tests: an integration test upserting a
mixed batch (one pre-existing row updated, two new rows inserted) in a single call.

**Scope note:** the related N+1 read in `GetPortfolioValuationHistoryService.loadPrices` (one
`getPriceHistory` call per ticker) was intentionally left as-is — a bulk store-only read doesn't
cleanly compose with `MarketDataService.getPriceHistory`'s per-symbol coverage/fetch-if-missing
logic, and forcing it in this pass risked breaking that invariant. Flagged here as a possible
follow-up rather than done speculatively.

### ⏸ 🟠 S8 — Same-day ordering can produce phantom oversells — DEFERRED

**Where:** `LedgerReplay.chronological`

Tiebreak is `createdAt` then `id.toString()` (random UUIDs). Bulk-imported same-day BUY+SELL
with identical timestamps can order SELL first and trip the oversell path even though the day
nets fine. Needs a stable user-controlled ordering (e.g. insertion sequence column).

**Status:** explicitly split out of Phase 3 for its own turn — larger footprint than the rest
of the phase (`Transaction` record constructor change across ~17 call sites, plus a schema
migration for the new `seq`/`BIGSERIAL` column). Not started.

### ✅ 🟡 M1 — CORS `origins=*` with attacker-settable `X-User-Id` — DONE (Phase 6)

**Where:** `application.properties:4-7`

`x-user-id` is in the allowed CORS headers, so any web page that can reach the service can
read any user's data from a browser. The VPC-edge trust model is documented, but the wildcard
undermines defense-in-depth. Restrict origins (env-configurable allowlist).

**Resolution:** `quarkus.http.cors.origins` now reads `${CORS_ALLOWED_ORIGINS:http://localhost:3000}`
instead of `*`; production sets `CORS_ALLOWED_ORIGINS` to the real frontend origin(s). No code
changes to `UserIdentityFilter`/the header-trust model itself — this only closes the
browser-reachable gap.

### ✅ 🟡 M2 — Dead code / dead config — PARTIALLY DONE (Phase 6)

- `MarketDataPort.getFxHistory` + provider/store FX-history methods — **correction to this
  finding:** confirmed *not* dead. It's live-wired to `/daily-history/portfolio/valuation` and
  `/performance-inputs` (both call it via `GetPortfolioValuationHistoryService`/
  `GetPerformanceInputsService`). Left untouched.
- `Exchange` model + `exchanges` table: confirmed genuinely dead (no model class exists; the
  only code reference anywhere is a comment) — **dropped** via Liquibase changeset
  `004-drop-exchanges-table` plus trimming the `CREATE TABLE`/seed `INSERT` from `schema.sql`.
- Caffeine cache config (`stock-prices`, `exchange-rates`): confirmed still dead (no
  `@CacheResult` anywhere) — **left as-is**, no decision was made to wire it in or remove it.
- `fractional` / `fractionalMultiplier`: confirmed still persisted but ignored by
  `LedgerReplay` — **left as-is**, same reason.

### ✅ 🟡 M3 — `commissionCurrency` ignored — DONE (Phase 6)

Buy fees are added raw into cost basis (`quantity*price + fees`) even when the fee currency
differs from the transaction currency.

**Resolution:** converted at write time rather than at every `LedgerReplay` read-side caller —
consistent with the write-boundary-prevention approach used for the C4 oversell fix.
`CreateTransactionService`/`UpdateTransactionService` now take `MarketDataPort` and, when
`commissionCurrency != currency` and the fee is non-zero, convert via `getFxRate` (falling back
to rate 1 on FX failure, same idiom as `GetPortfolioSummaryService.convertToBase`) before
storing. `commissionCurrency` itself is kept as originally provided (audit trail); only the
stored `fees` value changes. On update, conversion only runs when the request supplies a fresh
raw fee amount (`command.fees() != null`) — a carried-over existing fee is already expressed in
the transaction's currency from when it was first written, so reconverting it on every unrelated
edit would double-convert it.

### ✅ 🟡 M4 — Manual prices silently expire after 15 minutes — DONE (Phase 6)

`setManualPrice` stores a MANUAL quote, but `getSpotPrice` treats it like any cached quote:
once past `spot-price-freshness` the provider overwrites it. Manual overrides for symbols the
provider can't price re-break every 15 minutes.

**Resolution:** `getSpotPrice` now treats a `QuoteSource.MANUAL` quote as always fresh,
regardless of `asOf`/`spot-price-freshness`. Since that means a MANUAL quote never reverts to
provider pricing on its own, added `MarketDataPort.clearManualPrice` (deletes the stored quote,
falling through to the normal provider-fetch-on-next-read path) + `ClearManualPriceUseCase`/
`ClearManualPriceService` + `DELETE /api/positions/ticker/{ticker}/price`.

### ✅ 🟡 M5 — No range caps or pagination — DONE (Phase 6)

`from=1900-01-01` makes `datesBetween` build ~46k-element lists per symbol; search/history
endpoints return unbounded lists. Cap the date range (e.g. max 10y, `to` ≤ today) and page
large responses.

**Resolution:** added `application.portfolio.max-query-range-days` (default 3650) and extended
the existing `from`/`to` validation in all five range-driven history services
(`GetDailyPositionHistoryService`, `GetPriceHistoryService`, `GetPortfolioValuationHistoryService`,
`GetCapitalFlowsService`, `GetPerformanceInputsService`) to reject a span exceeding it — bounds
`MarketDataService.datesBetween` and the day-by-day valuation loop transitively, since both are
only ever invoked with an already-capped range. Transaction search (`/api/transactions/search`)
gets offset/limit pagination instead (its `from`/`to` are independent optional filters, not a
mandatory range that explodes into a day-by-day list, so a range cap wasn't the applicable fix
there) — added nullable `limit`/`offset` to `SearchTransactionsUseCase.Query`, threaded through
`TransactionRepository`/`TransactionPanacheRepository` via Panache's `.range(start, end)`;
omitting them preserves today's unpaginated behavior on `/api/transactions` and
`/api/transactions/ticker/{ticker}`.

### ✅ 🟡 M6 — Cron ingests every ticker any user ever typed, forever — DONE (Phase 4)

`findDistinctTickers` was global: closed positions, typos, all users, no cleanup. Combined
with C2 this was the most likely real-world trigger of coverage poisoning.

**Resolution:** `TransactionRepository.findDistinctTickers()` (a raw SQL distinct scan) is
gone; the cron's no-ticker path (`TriggerPriceIngestionService.resolveSymbols`) now calls the
new `TransactionRepository.findAll()` (all users' transactions) and derives open-position
tickers itself via `LedgerReplay.replayAll` per user, keeping only tickers where
`Position.active()` is true. Deliberately *not* a naive SQL sum: `LedgerReplay.applySplit`
scales `sharesOwned` multiplicatively, so a plain signed-quantity sum would misclassify tickers
with a SPLIT in their history. A ledger-inconsistent user (oversold ledger predating the C4
fix, or bypassing it) is logged and skipped rather than aborting ticker resolution for every
other user — mirrors the per-symbol isolation added for S6, applied here to per-user ticker
resolution. Tests: a ticker with only a BUY is included; a ticker fully bought-then-sold
(closed position) is excluded and never reaches `marketDataPort`.

### ✅ 🟡 M7 — DIVIDEND capital flows are gross of fees — DONE (Phase 5)

BUY/SELL flows are net of fees; DIVIDEND is gross (`quantity × price`, fees only recorded in
the `fees` field). Fine if intentional — document it for `/performance-inputs` consumers.

**Resolution:** `LedgerReplay.toCapitalFlow`'s DIVIDEND branch now subtracts fees
(`quantity × price - fees`), mirroring the SELL treatment, so all three flow kinds are
consistently fee-adjusted.

### ✅ 🟡 M8 — `hasActiveRun` check-then-act race — DONE (Phase 4)

Two concurrent triggers could both pass the check and start runs (the "check" and the "act"
were separate transactions).

**Resolution:** collapsed into one atomic port method,
`PriceIngestionRunRepository.startRunIfNoneActive(staleAfter, candidate)`, replacing
`hasActiveRun`. It acquires a single fixed-key Postgres advisory lock
(`pg_advisory_xact_lock`) for its whole span — reap stale runs, count active runs, and
conditionally save `candidate` — all inside one `@WithTransaction`, so a second concurrent
caller blocks until the first's transaction commits and then sees the up-to-date state. Same
advisory-lock technique as C4's `withTickersLocked`, just with one global key instead of
per-(userId, ticker) keys, since there is only ever one ingestion-trigger slot. Tests: seeded
active run → `startRunIfNoneActive` returns empty and the candidate is never persisted; no
active run → candidate is saved and returned.

---

## Action plan

Ordered so that data-destroying bugs stop first, then correctness, then robustness/cleanup.
Each phase is independently shippable.

### ✅ Phase 1 — Stop the bleeding (market-data store integrity): C2, C3 — DONE

1. In `TwelveDataProviderAdapter`, make `warnIfProviderError` **throw** a dedicated
   `MarketDataProviderException` on `status=error` (keep the message/code). Applies to all
   four fetch methods.
2. In `MarketDataService.getPriceHistory`/`getFxHistory`:
   - Only call `recordCoverage` after a successful provider fetch.
   - Clamp recorded coverage `to` at `min(requested to, last date returned, yesterday)` so
     open ranges are never marked covered. Skip recording entirely if nothing was returned.
   - Clamp/reject requested `to` in the future (clamp to today).
3. **One-off data repair:** delete `market_data_coverage` rows whose `to_date >= fetched_at`
   date or that overlap ranges with no matching `price_history` rows — they are potentially
   poisoned. Simplest safe option: `TRUNCATE market_data_coverage` (it only exists to avoid
   re-fetching; a truncate merely costs some refetches).
4. Tests: provider-error → no coverage recorded, no empty upsert; open-ended range → coverage
   clamped; poisoned-coverage scenario refetches after repair.

### ✅ Phase 2 — Correct multi-currency valuations: C1, S1 (+ groundwork for M3) — DONE

1. Extend `Currency` enum (CHF, AUD, HKD, SEK, NOK at minimum — match the `exchanges` seed)
   and extend the Postgres `currency_type` enum via a Liquibase changeset
   (`ALTER TYPE ... ADD VALUE`).
2. Handle `GBp`/`GBX` in `parsePriceSeries`: divide close by 100, store as GBP (use the
   `exchanges.price_multiplier` data — either query the table or fold the mapping into code
   and drop the table; **pick one, don't keep both**).
3. Stop defaulting unknown currencies to USD — fail the parse for that symbol (with C2's error
   handling this becomes a visible, non-poisoning failure).
4. In `GetPortfolioValuationHistoryService`:
   - Collect the set of currencies from snapshots + price entries.
   - Fetch `getFxHistory(currency, baseCurrency, from.minusDays(7), to)` for each non-base
     currency (sequentially, same session constraint as prices).
   - Convert each ticker's `marketValue` with the FX floor rate for that date; convert cost
     basis with the same rate. Mark the day `complete=false` when an FX rate is missing.
5. Decide and document capital-flow currency semantics for `/performance-inputs`: convert
   flows to base currency using the flow date's FX rate (recommended, consistent with
   valuations).
6. Tests: mixed-currency portfolio valuation vs hand-computed expectation; GBp symbol stored
   correctly; summary vs valuation-history agreement for the same instant.

### ✅ Phase 3 — Ledger invariants: C4, S8 — CORE DONE, S8 DEFERRED, PLAN REVISED

1. ✅ Write-time validation in `CreateTransactionService`, `UpdateTransactionService`,
   `DeleteTransactionService`: load the affected ticker's transactions (both old and new
   ticker on update), apply the candidate change in memory, run `LedgerReplay.replayTicker`;
   on `Oversell` return a 4xx `Result.Conflict`-style error naming the date and shortfall. Also
   made concurrency-safe (see C4 resolution note above) — not in the original plan, added after
   the initial write-time-only implementation because the read-validate-write sequence was
   TOCTOU-vulnerable across separate reactive transactions.
2. ❌ **Not built as originally planned.** Read-side graceful degradation (sealed
   `ReplayResult.Inconsistent` + `inconsistentTickers` field) was explicitly reverted per
   direction: prevent inconsistencies at the write boundary instead of building read-side
   reporting machinery for them. What shipped instead: `LedgerReplay` throws one dedicated
   `LedgerInconsistencyException`, caught at each read-side service and mapped to
   `Result.Conflict` → REST 409 — a thin defensive catch, not a degrade-and-report layer.
3. ⏸ Same-day determinism (S8): **not done**, deferred — see S8 above.
4. ✅ Tests: oversell rejected on create/update/delete; retroactive-oversell update rejected;
   concurrent racing requests serialize correctly (real two-thread HTTP test against
   Testcontainers Postgres); read-side inconsistency returns `Result.Conflict`/409, not a 500.
   (Same-day-buy+sell determinism test not written — blocked on S8.)

### ✅ Phase 4 — Ingestion robustness: C5, S6, M8, (M6) — DONE

1. ✅ Stale-run recovery: reap runs with `coalesce(started_at, created_at) < now() - staleAfter`
   as dead → mark FAILED with "Ingestion run timed out". Interval is configurable
   (`application.market-data.ingestion.stale-run-timeout`, default `PT3H`), not hardcoded.
   Removes the permanent Conflict lockout. See C5 above.
2. ✅ Per-symbol error isolation in `ingestSymbols`: `onFailure().recoverWithItem` per symbol,
   accumulate failures into `errorMessage`, complete the run with the new `PARTIAL` status when
   some (but not all) symbols failed. See S6 above.
3. ✅ Throttled provider calls: fixed delay between symbol fetches
   (`application.market-data.ingestion.symbol-fetch-delay`, default `PT8S`) to respect the
   TwelveData free-tier plan. See S6 above.
4. ✅ (M6) Scoped the cron's symbol list to tickers with currently open positions, derived via
   `LedgerReplay` rather than a raw distinct-ticker scan. See M6 above.
5. ✅ (M8, beyond the original plan) Closed the `hasActiveRun` check-then-act race with a single
   atomic advisory-locked `startRunIfNoneActive` port method — same category of fix as C4's
   concurrency hardening in Phase 3. See M8 above.
6. ✅ Tests: run continues past a failing symbol and ends `PARTIAL` with an accurate
   `symbolsCompleted` count; stale RUNNING row doesn't block a new trigger; closed positions are
   excluded from cron scoping; concurrent-trigger race closed at the repository level (seeded
   active run → candidate never persisted).

### ✅ Phase 5 — Valuation quality: S2, S3, S4, S5, M7 — DONE

1. ✅ `dailySnapshots`: collapsed to last state per (ticker, day) via a per-ticker
   `TreeMap<LocalDate, DailyPositionSnapshot>` upsert; `/api/daily-history/positions` output
   loses duplicates. See S2 above.
2. ✅ Price history now fetched from `from.minusDays(PRICE_STALENESS_DAYS)` (5 days, not the
   plan's suggested 7 — matches the exact staleness window `floorWithinStaleness` uses) so
   floor lookups work at range start. See S3 above.
3. ✅ Cost basis now always accumulates as soon as the FX rate is known; only marketValue gates
   on price availability. See S4 above.
4. ✅ Added a `complete` boolean to `PortfolioSummary` (mirroring `DailyValuation.complete`
   rather than a `pricedPositions`/`totalPositions` pair) — minimal, low-risk signal that a
   spot-price fetch failed, without changing how marketValue/cost/gain-loss are computed. See
   S5 above.
5. ✅ (M7, bundled into this phase alongside the LedgerReplay work in step 1) DIVIDEND capital
   flows now net of fees, consistent with BUY/SELL. See M7 above.
6. ✅ Tests: same-day buy+sell collapses to one snapshot; dividend fee netting; stale-price cost
   still included; foreign-currency stale-price-with-FX cost still included; FX-missing still
   excludes cost; `complete=false` on spot-price failure.

### ✅ Phase 6 — Performance & hygiene: S7, M1, M2, M4, M5, M3 — DONE

1. ✅ Batched native upserts (`ON CONFLICT DO UPDATE`) for prices and FX. See S7 above.
   **Deviation:** the related `symbol IN (:symbols)` bulk read for valuation price loading was
   *not* done — see the scope note under S7 for why it was deferred rather than forced through.
2. ✅ CORS: env-configured origin allowlist (`CORS_ALLOWED_ORIGINS`); wildcard removed. See M1
   above.
3. ✅ Dead config/code, revised per Phase 6 planning discussion: only `exchanges`/`Exchange`
   dropped (confirmed genuinely dead). Caffeine cache blocks and
   `fractional`/`fractionalMultiplier` deliberately left untouched — no decision was made to
   wire them in or remove them, and `MarketDataPort.getFxHistory` was found to *not* be dead
   (corrected out of this item entirely). See M2 above.
4. ✅ Manual price protection: `MANUAL` quotes now skip the freshness/provider-refresh check
   entirely (chosen over a TTL), plus a new explicit `clearManualPrice` endpoint to revert to
   provider pricing. See M4 above.
5. ✅ Request guards: `application.portfolio.max-query-range-days` (default 3650) added to the
   five range-driven history services; offset/limit pagination added to
   `/api/transactions/search`. See M5 above.
6. ✅ M3: `commissionCurrency` fees now converted at write time (not booking-time-only as
   phrased in the original plan wording, but the same effect — converted once when the
   transaction is created/updated, never re-derived at read time). See M3 above.
   **Deviation:** documenting dividend-flow gross/net semantics in the OpenAPI description was
   not done — M7's fix (net of fees, matching BUY/SELL) made the documentation question moot,
   since flows are now uniformly net rather than needing a documented exception.

---

## Suggested sequencing note

Phases 1–2 change stored data semantics (coverage rows, currency values). Do the
`market_data_coverage` cleanup in Phase 1 and re-ingest prices **after** Phase 2 lands, so
GBp/CHF/etc. are re-stored correctly in one pass.

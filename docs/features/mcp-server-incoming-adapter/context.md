# MCP Server Incoming Adapter — Context

## Problem statement and goal

The portfolio service is consumed today through a REST API. AI agents (LLM-based assistants) increasingly consume services through the Model Context Protocol (MCP) instead of raw HTTP: MCP gives them a discoverable, self-describing catalog of callable tools with typed, validated inputs.

**Goal:** expose the service's existing capability surface as an MCP server — a new *incoming layer* alongside REST — so that any MCP client (Claude, agent orchestrators, IDEs) can operate the portfolio with the same behaviors, business rules, and per-user isolation the REST API enforces. The REST API remains unchanged and fully functional.

"Replicate the REST layer" means **capability parity, not endpoint-count parity**: every operation reachable over REST is reachable over MCP with identical business behavior. Convenience endpoint variants (list-all vs. list-by-ticker vs. search; count vs. count-by-ticker; all vs. active) are folded into single tools with optional arguments, because a compact, well-described tool catalog is easier for an agent to use correctly than 26 near-duplicate tools.

## Actors and identity

- **Caller:** an MCP client acting on behalf of exactly one end user.
- **Identity:** the user is identified by the `X-User-Id` HTTP header on each MCP request, exactly as in REST (trusted-upstream model; the header is set by the MCP client's connection configuration, not chosen by the language model). User identity is **never a tool argument** — a tool call must not be able to name an arbitrary user.
- Every tool operates strictly on the calling user's data. Two users calling the same tool with the same arguments see only their own transactions/positions.

### Identity behaviors

| Behavior | Observable outcome |
|---|---|
| Request carries non-blank `X-User-Id` | Tool executes for that user |
| Header missing or blank on a tool call | Tool call fails as an error result with code `MISSING_USER_ID` and a message telling the operator to configure the header; no domain operation runs |
| Header present but tool arguments invalid | Validation error (see below); no domain operation runs |

## Protocol-level contract

- Transport: **Streamable HTTP** at a stable endpoint path (`/mcp`) on the same server/port as REST. (stdio/desktop-spawned mode is out of scope.)
- The server answers MCP `initialize` and advertises the **tools** capability, a server name identifying this service, and its version.
- `tools/list` returns the catalog below. Every tool has:
  - a clear, action-oriented **description** stating what it does, when to use it, and what it returns;
  - a JSON **input schema** in which every argument carries a description, type, and its validation constraints (required flags, min values, max lengths, enum values, date format);
  - a JSON **output schema**, and successful calls return machine-readable **structured content** matching it (a JSON object; list results are wrapped in an object).
- **Expected business rejections** (invalid request, not found, conflict) are returned as **tool error results** (`isError: true`) whose text starts with a stable error code (`INVALID_REQUEST`, `NOT_FOUND`, `CONFLICT`, …) followed by a human/model-readable explanation. They are *results*, not protocol failures — the MCP session stays healthy and the model can read the message and correct itself.
- **Input validation failures** (schema/constraint violations) are also tool error results, listing each violated constraint so the model can repair its arguments.
- Tool calls never leak stack traces, SQL, or upstream-provider payloads in any outcome.

## Enumerations used in inputs/outputs

- `transactionType`: `BUY`, `SELL`, `DIVIDEND`, `SPLIT`
- `assetType`: `AMERICAN_DEPOSITARY_RECEIPT`, `BOND`, `BOND_FUND`, `CLOSED_END_FUND`, `COMMON_STOCK`, `DEPOSITARY_RECEIPT`, `DIGITAL_CURRENCY`, `ETF`, `EXCHANGE_TRADED_NOTE`, `GLOBAL_DEPOSITARY_RECEIPT`, `LIMITED_PARTNERSHIP`, `MUTUAL_FUND`, `PHYSICAL_CURRENCY`, `PREFERRED_STOCK`, `REIT`, … (the service's full asset-type set)
- `currency`: `USD`, `EUR`, `GBP`, `CAD`, `JPY`, `CHF`, `AUD`, `HKD`, `SEK`, `NOK`
- `sort`: `ASC`, `DESC`
- ingestion run `status`: `PENDING`, `RUNNING`, `COMPLETED`, `PARTIAL`, `FAILED`
- Dates are ISO-8601 (`YYYY-MM-DD`); timestamps are ISO-8601 with offset.

## Tool catalog (functional requirements)

Each tool below maps 1:1 to an existing use case; behavior (validation, rejection reasons, data returned) is identical to the corresponding REST endpoint.

### Transactions

**1. `create_transaction`** — records a new transaction in the user's ledger. *(REST: `POST /api/transactions`)*
- Inputs: `ticker` (required, ≤20 chars), `transactionType` (required enum), `assetType` (required enum), `quantity` (required, ≥ 0.000001), `price` (required, ≥ 0), `currency` (required enum), `transactionDate` (required date), `exchange` (required, ≤20), `country` (required, ≤50), optional: `fees` , `notes`, `isFractional`, `fractionalMultiplier`, `commissionCurrency`, `companyName`.
- Success: the created transaction (all fields below, including generated `id`, `totalValue`, `totalCost`, timestamps).
- Rejections: `INVALID_REQUEST` (domain validation), `CONFLICT` (ledger conflict).

**2. `get_transaction`** — fetches one transaction by id. *(REST: `GET /api/transactions/{id}`)*
- Inputs: `id` (required, UUID).
- Success: the transaction. Rejection: `NOT_FOUND`.

**3. `search_transactions`** — lists/searches the user's transactions. Replaces three REST endpoints (list all, list by ticker, search with filters).
- Inputs (all optional): `ticker`, `type` (enum), `fromDate`, `toDate`, `sort` (default `DESC`), `limit`, `offset`.
- Success: `{ transactions: [...], count }` sorted by transaction date per `sort`.
- Rejection: `INVALID_REQUEST` (e.g. invalid range/pagination — same domain rules as REST).

**4. `update_transaction`** — updates fields of an existing transaction. *(REST: `PUT /api/transactions/{id}`)*
- Inputs: `id` (required, UUID); every other create-field optional, constraints as in create when present.
- Success: the updated transaction. Rejections: `NOT_FOUND`, `INVALID_REQUEST`, `CONFLICT`.

**5. `delete_transaction`** — deletes a transaction. *(REST: `DELETE /api/transactions/{id}`)*
- Inputs: `id` (required, UUID).
- Success: confirmation `{ deleted: true, id }`. Rejections: `NOT_FOUND`, `CONFLICT` (e.g. deletion would corrupt the ledger).

**6. `count_transactions`** — counts the user's transactions, optionally for one ticker. *(REST: `GET /api/transactions/count`, `/count/{ticker}`)*
- Inputs: `ticker` (optional).
- Success: `{ count }`. No rejections.

Transaction object fields: `id`, `ticker`, `transactionType`, `assetType`, `quantity`, `price`, `fees`, `currency`, `transactionDate`, `notes`, `isFractional`, `fractionalMultiplier`, `commissionCurrency`, `exchange`, `country`, `companyName`, `totalValue`, `totalCost`, `createdAt`, `updatedAt`.

### Positions (derived on read from the ledger)

**7. `list_positions`** — lists the user's positions. *(REST: `GET /api/positions`, `/active`, `/count`, `/count/active`)*
- Inputs: `activeOnly` (optional boolean, default `false`).
- Success: `{ positions: [...], count }`.
- Rejection: `CONFLICT` (ledger in a state from which positions cannot be derived).

**8. `get_position`** — fetches the position for one ticker. *(REST: `GET /api/positions/ticker/{ticker}`, `/exists`)*
- Inputs: `ticker` (required), `includeMarketPrice` (optional boolean, default `true`; `false` answers existence/holdings questions without touching market data).
- Success: the position. Rejection: `NOT_FOUND` (an agent checking existence treats `NOT_FOUND` as "no position").

**9. `set_position_price`** — manually overrides the latest market price for a ticker. *(REST: `PUT /api/positions/ticker/{ticker}/price`)*
- Inputs: `ticker` (required), `price` (required, ≥ 0), `currency` (optional enum).
- Success: the refreshed position. Rejections: `NOT_FOUND`, `INVALID_REQUEST`.

**10. `clear_position_price`** — removes a manual price override, reverting to provider pricing. *(REST: `DELETE /api/positions/ticker/{ticker}/price`)*
- Inputs: `ticker` (required).
- Success: the refreshed position. Rejection: `NOT_FOUND`.

Position object fields: `ticker`, `companyName`, `totalQuantity`, `averagePrice`, `currentPrice`, `totalCost`, `currency`, `firstPurchaseDate`, `isActive`, `marketValue`, `unrealizedGainLoss`, `unrealizedGainLossPercentage`, `exchange`, `country`.

### Portfolio

**11. `get_portfolio_summary`** — aggregated portfolio totals. *(REST: `GET /api/portfolio/summary`, `/summary/active`)*
- Inputs: `activeOnly` (optional boolean, default `false`).
- Success: `{ valuationCurrency, totalMarketValue, totalCost, totalUnrealizedGainLoss, totalUnrealizedGainLossPercentage, totalPositions, activePositions, complete }`.
- Rejection: `CONFLICT`.

### Daily history & quant inputs

Common inputs: `from`, `to` (optional dates; the same domain range rules as REST apply — invalid ranges are rejected `INVALID_REQUEST`).

**12. `get_daily_position_history`** — daily position snapshots. *(REST: `GET /api/daily-history/positions`)*
- Inputs: `from`, `to`, `ticker` (all optional).
- Success: `{ snapshots: [{ ticker, snapshotDate, sharesOwned, averageCostPerShare, totalInvestedAmount, currency }] }`.
- Rejections: `INVALID_REQUEST`, `CONFLICT`.

**13. `get_price_history`** — historical prices for symbols. *(REST: `GET /api/daily-history/prices`)*
- Inputs: `symbols` (required, non-empty array of ticker strings), `from`, `to` (optional).
- Success: `{ entries: [{ symbol, priceDate, closePrice, adjustedClosePrice, currency, fetchedAt }] }`.
- Rejection: `INVALID_REQUEST`.

**14. `get_portfolio_valuation_history`** — daily portfolio valuations. *(REST: `GET /api/daily-history/portfolio/valuation`)*
- Inputs: `from`, `to` (optional).
- Success: `{ valuations: [{ date, valuationCurrency, totalMarketValue, totalCost, complete }] }`.
- Rejections: `INVALID_REQUEST`, `CONFLICT`.

**15. `get_capital_flows`** — capital flows projected from the ledger. *(REST: `GET /api/daily-history/portfolio/capital-flows`)*
- Inputs: `from`, `to` (optional).
- Success: `{ flows: [{ transactionId, ticker, flowDate, kind, currency, amount, fees }] }`.
- Rejection: `INVALID_REQUEST`.

**16. `get_performance_inputs`** — bundled valuations + capital flows for quant performance computation. *(REST: `GET /api/daily-history/portfolio/performance-inputs`)*
- Inputs: `from`, `to` (optional).
- Success: `{ valuations: [...], capitalFlows: [...], incompleteDays }`.
- Rejections: `INVALID_REQUEST`, `CONFLICT`.

### Price ingestion

**17. `trigger_price_ingestion`** — starts an asynchronous price-history ingestion run. *(REST: `POST /api/price-ingestion/runs`)*
- Inputs (all optional): `from`, `to`, `ticker`, `includeBackfill` (default `false`).
- Success: `{ runId }` — the run is **accepted**, not completed; callers poll with tool 18.
- Rejections: `CONFLICT` (e.g. a run already in progress), `INVALID_REQUEST`.

**18. `get_price_ingestion_run`** — status of the user's latest ingestion run. *(REST: `GET /api/price-ingestion/runs/latest`)*
- Inputs: none.
- Success: `{ id, status, fromDate, toDate, ticker, symbolsRequested, symbolsCompleted, errorMessage, startedAt, completedAt }` — the fields the port's `RunStatus` projection exposes (`includeBackfill`/`createdAt`/`updatedAt` are on the domain `IngestionRun` but not surfaced through this use case, matching the REST endpoint's own payload).
- Rejection: `NOT_FOUND` (user never triggered a run).

## Business rules and edge cases

1. **Behavioral parity with REST:** for any given user state and equivalent input, an MCP tool and its REST counterpart produce the same business outcome (same success payload data, same rejection reason). The domain rules live in the core and are not duplicated or altered by this feature.
2. **Rejections are values, not session failures:** a `NOT_FOUND`/`CONFLICT`/`INVALID_REQUEST` outcome must leave the MCP session usable; a subsequent valid call succeeds.
3. **Validation happens before execution:** when arguments violate declared constraints, no domain state changes and the error text names each violated field/constraint.
4. **Mutating tools** (`create_transaction`, `update_transaction`, `delete_transaction`, `set_position_price`, `clear_position_price`, `trigger_price_ingestion`) change state only on success/accepted outcomes. Read tools never change state (except that reading market prices may refresh cached quotes, exactly as in REST).
5. **Schema completeness is part of the contract:** `tools/list` must show, for every tool, non-empty descriptions on the tool and on every argument, required/optional flags, enum value lists, and numeric/length bounds — an agent must be able to call any tool correctly from the catalog alone.

## Failure modes

- **Rollback:** an unexpected failure inside a mutating tool rolls back its database work (the underlying use-case/transaction semantics — unchanged by this feature). The tool reports a sanitized error result; nothing half-written is visible afterward.
- **Upstream market-data failures** surface exactly as in REST, but as tool error results with the same stable codes and no leaked provider detail:
  - provider budget exhausted → `RATE_LIMITED` + "retry after N seconds" guidance;
  - provider outage/error → `MARKET_DATA_UNAVAILABLE` + retry guidance;
  - unresolvable symbol → `MARKET_SYMBOL_UNRESOLVED` + "verify ticker, exchange, and country".
- **Unexpected internal failures** → `INTERNAL_ERROR` with a correlation id and a generic message; details go to server logs only.
- **Retries:** all read tools and `trigger_price_ingestion` conflicts are safe to retry; the error text for retryable outcomes says so.

## Out of scope (explicit)

- stdio and WebSocket transports; SSE (deprecated by the MCP spec).
- MCP resources, prompts, sampling, elicitation — tools only.
- Authentication/authorization beyond the existing `X-User-Id` trusted-header model (no OIDC/OAuth in this feature).
- Any change to REST endpoints, core use cases, domain rules, or persistence.
- New business capabilities not present in REST today.
- Rate limiting / quota enforcement specific to MCP clients.

## External contract summary

- New HTTP endpoint `/mcp` (Streamable HTTP MCP) on the existing server, coexisting with `/api/*` REST and `/q/*` management endpoints, which behave exactly as before.
- The MCP tool catalog above **is** the contract; input/output schemas as described, stable tool names, stable error-code prefixes in error-result text.
- No database schema changes. No event schema changes.

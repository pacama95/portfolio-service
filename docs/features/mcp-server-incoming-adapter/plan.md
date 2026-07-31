# MCP Server Incoming Adapter — Implementation Plan

Behavioral contract: [context.md](./context.md). This plan follows the project rules in `.cursor/rules/` — cited inline as **[rule-name]**.

## Technology decision (investigated 2026-07)

**Chosen: Quarkiverse `quarkus-mcp-server` 1.13.1 (latest stable, released 2026-07-02), Streamable HTTP transport.**

| Option | Verdict |
|---|---|
| **Quarkiverse `quarkus-mcp-server-http` 1.13.1** | ✅ Chosen. Declarative `@Tool`/`@ToolArg` with CDI, native Mutiny `Uni` support (fits our reactive-end-to-end rule), build-time discovery/validation, Dev UI tool tester, GraalVM native support, Quarkus Security integration. Streamable HTTP is the MCP spec's preferred transport (SSE deprecated since spec 2025-03-26). |
| `quarkus-mcp-server-hibernate-validator` 1.13.1 | ✅ Chosen (companion). Jakarta Bean Validation on tool args: violations become `isError: true` tool results listing each violation, and constraints are reflected into the generated JSON input schema (via `jsonschema-module-jakarta-validation`). Exactly the "validate inputs" requirement. |
| Official MCP Java SDK (`io.modelcontextprotocol.sdk`) | ❌ Rejected: lower-level (manual server/transport/handler wiring), no CDI/build-time validation, no Quarkus dev mode/native integration. Right choice outside Quarkus; strictly more work here. |
| `quarkus-mcp-server` 2.0.0.Beta3 | ❌ Rejected for now: still beta (stateless-protocol support, `@McpParamHeader`). Revisit when 2.0 goes final; migration is annotation-compatible. |
| stdio / WebSocket transports | ❌ Out of scope: stdio is for desktop-spawned single-user servers; this is a long-running multi-user service. WebSocket is unofficial. |

Key extension facts used below:
- Tool methods: CDI bean methods annotated `@Tool(name, description, structuredContent = true)`, args annotated `@ToolArg(description, required = false, defaultValue = ...)`; may return `Uni<T>` — `T` is serialized as structured content and its JSON output schema is generated automatically.
- Expected-failure channel: throwing `ToolCallException(message)` (or failing the `Uni` with it) produces an `isError: true` tool result with that message; the session stays healthy.
- HTTP specifics: endpoint at `quarkus.mcp.server.http.root-path` (default `mcp`); the current request's headers are readable by injecting `io.vertx.core.http.HttpServerRequest` into the tool bean (maintainer-confirmed pattern, quarkiverse/quarkus-mcp-server#299).
- Test support: `io.quarkiverse.mcp:quarkus-mcp-server-test` ships **McpAssured** (`McpStreamableTestClient`) for `@QuarkusTest` integration tests, including custom headers and `isError`/structured-content assertions.

## Affected components

- `build.gradle` — three new dependencies (no version catalog in use; pin `1.13.1`):
  - `implementation 'io.quarkiverse.mcp:quarkus-mcp-server-http:1.13.1'`
  - `implementation 'io.quarkiverse.mcp:quarkus-mcp-server-hibernate-validator:1.13.1'`
  - `testImplementation 'io.quarkiverse.mcp:quarkus-mcp-server-test:1.13.1'` (the Quarkus Gradle plugin's `integrationTest` source set inherits `testImplementation`, as with rest-assured today)
- `src/main/resources/application.properties` — MCP server config block (see step 1).
- **New package `com.portfolio.adapters.incoming.mcp`** — the entire feature lives here; a new *driving adapter kind* beside `rest`, per **[hexagonal-architecture]**. No changes to `core` (ports already exist and are the only thing the adapter calls), no changes to `adapters/incoming/rest`, no DB migrations.

```
adapters/incoming/mcp
├── McpUserIdentity.java            # X-User-Id resolution from HttpServerRequest (+ MDC userId)
├── McpFailureTranslator.java       # sealed Error hierarchies -> sanitized ToolCallException
├── TransactionMcpTools.java        # tools 1–6
├── PositionMcpTools.java           # tools 7–10
├── PortfolioMcpTools.java          # tool 11
├── DailyHistoryMcpTools.java       # tools 12–16
├── PriceIngestionMcpTools.java     # tools 17–18
├── dto                             # MCP-owned response records (adapter owns its DTOs — [hexagonal-architecture]); tool inputs are scalar @ToolArg parameters, not records — see deviation note below
│   ├── TransactionDto.java  TransactionListDto.java  TransactionCountDto.java  DeleteConfirmationDto.java
│   ├── PositionDto.java  PositionListDto.java  PortfolioSummaryDto.java
│   ├── DailyPositionHistoryDto.java  PriceHistoryDto.java  ValuationHistoryDto.java
│   ├── CapitalFlowsDto.java  PerformanceInputsDto.java
│   └── IngestionRunDto.java  IngestionAcceptedDto.java
└── mapper
    ├── TransactionMcpMapper.java   # MapStruct, componentModel = "cdi" — [mapstruct-mappers]
    ├── PositionMcpMapper.java
    └── HistoryMcpMapper.java
```

Notes on DTOs:
- List results get wrapper records (`TransactionListDto{transactions, count}` …) because MCP structured content must be a JSON object, and wrappers give agents a natural place for counts.
- **Deviation from the original draft, confirmed by experiment during step 1 (throwaway dual-tool comparison against 1.13.1's actual schema generation):** all tool *inputs* use individual scalar/enum `@ToolArg`-annotated method parameters, never a single composite `@Valid` record parameter. A composite record parameter serializes as one nested object property (`{"person": {...}}`) and — critically — field-level `@ToolArg` descriptions on the record's own fields aren't picked up (only the top-level `@ToolArg(description=...)` on the record parameter itself is used, e.g. "The person"), which fails context.md rule 5 ("every argument carries a description"). Individual scalar parameters produce a flat top-level schema with every field's own description, min/max, and required flag — exactly mirroring the REST request DTO's flat JSON body, and it's what Bean Validation's own docs example uses for scalar args. `create_transaction`/`update_transaction` therefore take ~14 individual parameters rather than one args record; this is normal for MCP tool signatures. Output/structured-content DTOs are unaffected — nesting there is fine since `@OutputSchema`/`structuredContent` generates schema from the return type directly, not from `@ToolArg`.
- All DTO records (outputs only, per the deviation above): constraint annotations do not apply to them; field descriptions are optional since output schemas are for the model to read data, not to know what to send — but `@RegisterForReflection` is still required (native, **[java-quarkus-conventions]**). `**/dto/**` is already Jacoco-excluded.
- History DTOs mirror the domain records field-for-field. The REST layer currently returns those domain records raw; the MCP adapter maps them into its own DTOs instead, as **[hexagonal-architecture]** requires ("each adapter package owns its own DTOs") — same wire fields, no rule deviation.

## Public contracts to introduce (signatures only)

```java
// Identity — MCP analog of rest.UserContext + UserIdentityFilter (X-User-Id trust model unchanged)
@ApplicationScoped
class McpUserIdentity {
    UserId requireUserId();      // reads X-User-Id from injected HttpServerRequest;
                                 // missing/blank -> ToolCallException("MISSING_USER_ID: ...");
                                 // sets MDC userId for log correlation — [logging]
}

// Failure translation — MCP analog of the four REST ExceptionMappers, same sanitized wording
@ApplicationScoped
class McpFailureTranslator {
    <T> Uni<T> sanitize(Uni<T> pipeline);
    // MarketDataProviderError.RateLimited      -> ToolCallException("RATE_LIMITED: ... retry after Ns")
    // MarketDataProviderError.SymbolResolution -> ToolCallException("MARKET_SYMBOL_UNRESOLVED: ...")
    // MarketDataProviderError.ProviderException-> ToolCallException("MARKET_DATA_UNAVAILABLE: ...")
    // anything else -> ToolCallException("INTERNAL_ERROR: ... correlationId=<traceId>") + ERROR log
}

// Tool method pattern (one per catalog entry; names/args per context.md).
// Inputs are individual scalar/enum @ToolArg parameters (flat schema — see deviation note above),
// not a composite request object.
@Tool(name = "create_transaction", description = "...", structuredContent = true)
Uni<TransactionDto> createTransaction(
        @ToolArg(description = "...") @NotBlank @Size(max = 20) String ticker,
        @ToolArg(description = "...") @NotNull TransactionType transactionType,
        @ToolArg(description = "...") @NotNull AssetType assetType,
        @ToolArg(description = "...") @NotNull @DecimalMin("0.000001") BigDecimal quantity,
        @ToolArg(description = "...") @NotNull @DecimalMin("0.0") BigDecimal price,
        @ToolArg(description = "...", required = false) BigDecimal fees,
        @ToolArg(description = "...") @NotNull Currency currency,
        @ToolArg(description = "...") @NotNull LocalDate transactionDate,
        @ToolArg(description = "...", required = false) String notes,
        @ToolArg(description = "...", required = false) Boolean isFractional,
        @ToolArg(description = "...", required = false) BigDecimal fractionalMultiplier,
        @ToolArg(description = "...", required = false) Currency commissionCurrency,
        @ToolArg(description = "...") @NotBlank @Size(max = 20) String exchange,
        @ToolArg(description = "...") @NotBlank @Size(max = 50) String country,
        @ToolArg(description = "...", required = false) String companyName);
```

Result mapping pattern (**[sealed-results-errors]**: outermost incoming adapter maps `Result` variants to responses; exhaustive `switch`, no `default`):

```java
return sanitize(useCase.execute(mapper.toCommand(identity.requireUserId(), args))
    .map(result -> switch (result) {
        case Result.Success s        -> mapper.toDto(s.transaction());
        case Result.InvalidRequest r -> throw new ToolCallException("INVALID_REQUEST: " + r.message());
        case Result.Conflict c       -> throw new ToolCallException("CONFLICT: " + c.message());
    }));
```

Business rejections stay *values* through the core (`Result` variants, transactions commit); only at this outermost boundary do they become `ToolCallException`, which the extension renders as an `isError` tool result — the MCP analog of REST's 4xx mapping. Sealed `Error` exceptions keep flowing through the failure channel (rollback already done) and are sanitized by the translator.

## Configuration (application.properties)

```properties
quarkus.mcp.server.server-info.name=portfolio-service
quarkus.mcp.server.server-info.version=1.0.0
quarkus.mcp.server.http.root-path=mcp
# JSON text fallback beside structured content, for older clients
quarkus.mcp.server.tools.structured-content.compatibility-mode=true
%dev.quarkus.mcp.server.traffic-logging.enabled=true
```

The existing JAX-RS `UserIdentityFilter` does not apply to `/mcp` (it is a Vert.x route, not JAX-RS) — no interference either way; MCP identity is handled by `McpUserIdentity`.

## Implementation order (each step compiles + its tests pass — [tdd-workflow])

1. **Dependencies + config + walking skeleton.** Add the three dependencies and the config block; create `McpUserIdentity` with one trivial `ping`-style tool temporarily guarded behind it; verify `./gradlew quarkusDev` boots, Dev UI lists the tool, and `X-User-Id` presence/absence behaves per contract. Confirms 1.13.1 ↔ Quarkus 3.33.2.1 compatibility immediately (top risk). Remove the throwaway tool at the end of the step.
2. **`McpUserIdentity` + `McpFailureTranslator` (TDD).** Unit tests first: header present/missing/blank; each sealed `Error` type → sanitized message; unknown failure → `INTERNAL_ERROR` + no internal detail in message.
3. **Transaction tools + DTOs + `TransactionMcpMapper` (TDD).** Tools 1–6 per catalog; unit tests mock the six use-case ports and assert every `Result` variant path (DTO fields on success; error-code prefix + message on rejections).
4. **Position + portfolio tools (TDD).** Tools 7–11, incl. `includeMarketPrice=false` → `withoutMarketPrice` query variant, `activeOnly` flag mapping.
5. **History + ingestion tools (TDD).** Tools 12–18; `symbols` as `@NotEmpty List<String>` (REST's CSV parsing is a REST-only concern).
6. **Integration tests** (`src/integrationTest/java/com/portfolio/integration/mcp/…`) — see strategy below.
7. **Docs + verification.** README section (endpoint, identity header, client config example); run the full pipeline `./gradlew build integrationTest` (coverage gates ≥ 90% line+branch apply to the new adapter classes — `gradle/testing.gradle`); smoke-test `tools/list` output for schema completeness (rule 5 of context.md).

## Testing strategy — [testing], [tdd-workflow]

**Unit (`src/test/java`, one class per production class, Mockito in code, no annotations):**
- Tool classes: use-case ports + `McpUserIdentity` mocked; subscribe with `UniAssertSubscriber` (never `.await().indefinitely()`); assert success DTO mapping and, per rejection variant, a `ToolCallException` whose message starts with the documented code.
- `McpUserIdentity`: mocked `HttpServerRequest` (header present / missing / blank), MDC set.
- `McpFailureTranslator`: each translation branch.
- Mappers: generated MapStruct impls (`Mappers.getMapper(...)`), never mocks; `unmappedTargetPolicy = ERROR` catches drift when DTOs/domain evolve.

**Integration (`@QuarkusTest` + McpAssured against the real `/mcp` endpoint, PostgreSQL Testcontainer, DBRider datasets, WireMock for market data — all already in place for REST ITs):**
- `McpToolCatalogIT` — `initialize` + `tools/list`: 18 tools, every tool/arg description non-empty, constraints and enums present in input schemas, output schemas present.
- `McpTransactionFlowIT` — create → search → update → count → delete against DBRider datasets with `@ExpectedDataSet` verification; validation-violation call (bad `quantity`) → `isError` listing the field, and DB unchanged; `NOT_FOUND`/`CONFLICT` paths; session stays usable after each rejection.
- `McpPortfolioAndHistoryIT` — positions/summary/history tools over a seeded ledger, WireMock-stubbed provider; provider 429 → `RATE_LIMITED` error result (parity with `RateLimitApiIT`).
- `McpIdentityIT` — no `X-User-Id` header → `MISSING_USER_ID` error result and no side effects; two users' data isolation through the same tool.
- Parity spot-check inside the flow ITs: same seeded state queried via REST (RestAssured) and MCP returns the same business data.

**Stubbed:** market-data HTTP (WireMock), never the internal use cases in ITs.

## Testing finding recorded during step 6

**McpAssured's chained `.toolsCall(...).toolsCall(...)....thenAssertResults()` dispatches every call in the batch concurrently, not in declaration order.** Confirmed by two failures: a search→update→count→delete→get chain read stale state (count came back 2 instead of 3 because delete raced ahead of count), and five independent read-only history calls hit a Hibernate Reactive session-sharing error (`Illegal pop() with non-matching JdbcValuesSourceProcessingState`) under concurrent execution. Fix applied throughout `src/integrationTest/java/com/portfolio/integration/mcp/`: **one `toolsCall(...)` per `.thenAssertResults()` batch**, always, even for calls with no declared dependency on each other. This matches real MCP client usage anyway (an agent awaits each tool result before deciding the next call).

## Risks & open questions

1. **Version compatibility** `quarkus-mcp-server:1.13.1` ↔ Quarkus platform `3.33.2.1` — expected fine (release cadence tracks current Quarkus); step 1 verifies before any real code. Fallback: latest 1.13.x patch.
2. **`HttpServerRequest` injection inside tool execution** is maintainer-confirmed but not first-class documented; step 1's skeleton proves it on the streamable-HTTP path. Fallback: custom Quarkus `HttpAuthenticationMechanism` producing a `SecurityIdentity` from `X-User-Id` (documented Quarkus Security integration).
3. **Structured-content shape details** (e.g. exact schema rendering of `BigDecimal`/dates) — verified in `McpToolCatalogIT`; adjust DTO types/annotations if a schema comes out unusable for agents.
4. **Tool-catalog size vs. context cost** — 18 tools is within normal client limits; if it ever grows, the extension supports multiple named servers (`@McpServer`) to segment catalogs. Not needed now.
5. **Decision taken (flagged for review): consolidated tools** (18 tools ↔ 18 use cases) instead of strict 1:1 endpoint mapping (26 tools). Rationale in context.md; switching to 1:1 is mechanical if preferred.
6. **Decision taken (flagged for review): identity via `X-User-Id` header** on MCP HTTP requests (client connection config), never as a tool argument. Matches the service's VPC-edge trust model; OIDC can be layered later via the extension's security integration without touching tool code.

package com.portfolio.adapters.incoming.mcp;

import com.portfolio.adapters.incoming.mcp.dto.IngestionAcceptedDto;
import com.portfolio.adapters.incoming.mcp.dto.IngestionRunDto;
import com.portfolio.adapters.incoming.mcp.mapper.HistoryMcpMapper;
import com.portfolio.core.ports.incoming.GetPriceIngestionRunUseCase;
import com.portfolio.core.ports.incoming.TriggerPriceIngestionUseCase;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;

@ApplicationScoped
class PriceIngestionMcpTools {

    private final TriggerPriceIngestionUseCase triggerPriceIngestionUseCase;
    private final GetPriceIngestionRunUseCase getPriceIngestionRunUseCase;
    private final HistoryMcpMapper mapper;
    private final McpUserIdentity identity;
    private final McpFailureTranslator failureTranslator;

    PriceIngestionMcpTools(
            TriggerPriceIngestionUseCase triggerPriceIngestionUseCase,
            GetPriceIngestionRunUseCase getPriceIngestionRunUseCase,
            HistoryMcpMapper mapper,
            McpUserIdentity identity,
            McpFailureTranslator failureTranslator) {
        this.triggerPriceIngestionUseCase = triggerPriceIngestionUseCase;
        this.getPriceIngestionRunUseCase = getPriceIngestionRunUseCase;
        this.mapper = mapper;
        this.identity = identity;
        this.failureTranslator = failureTranslator;
    }

    @Tool(name = "trigger_price_ingestion", description = "Starts an asynchronous price-history ingestion run. "
            + "Returns immediately with a run id; poll get_price_ingestion_run for status.", structuredContent = true)
    Uni<IngestionAcceptedDto> triggerPriceIngestion(
            @ToolArg(description = "Start date (ISO-8601), inclusive", required = false) LocalDate from,
            @ToolArg(description = "End date (ISO-8601), inclusive", required = false) LocalDate to,
            @ToolArg(description = "Restrict ingestion to a single ticker", required = false) String ticker,
            @ToolArg(description = "Whether to backfill historical gaps in addition to recent data",
                    required = false, defaultValue = "false") boolean includeBackfill) {
        return failureTranslator.sanitize(triggerPriceIngestionUseCase.execute(
                        new TriggerPriceIngestionUseCase.Command(identity.requireUserId(), from, to, ticker, includeBackfill))
                .map(result -> switch (result) {
                    case TriggerPriceIngestionUseCase.Result.Accepted accepted -> new IngestionAcceptedDto(accepted.runId());
                    case TriggerPriceIngestionUseCase.Result.Conflict conflict ->
                            throw new ToolCallException("CONFLICT: " + conflict.message());
                    case TriggerPriceIngestionUseCase.Result.InvalidRequest invalid ->
                            throw new ToolCallException("INVALID_REQUEST: " + invalid.message());
                }));
    }

    @Tool(name = "get_price_ingestion_run", description = "Status of the caller's latest price-history ingestion run.", structuredContent = true)
    Uni<IngestionRunDto> getPriceIngestionRun() {
        return failureTranslator.sanitize(getPriceIngestionRunUseCase.execute(
                        new GetPriceIngestionRunUseCase.Query(identity.requireUserId()))
                .map(result -> switch (result) {
                    case GetPriceIngestionRunUseCase.Result.Success success -> mapper.toDto(success.status());
                    case GetPriceIngestionRunUseCase.Result.NotFound ignored ->
                            throw new ToolCallException("NOT_FOUND: no ingestion run has been triggered yet");
                }));
    }
}

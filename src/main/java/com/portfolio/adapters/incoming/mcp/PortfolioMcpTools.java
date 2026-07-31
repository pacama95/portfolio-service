package com.portfolio.adapters.incoming.mcp;

import com.portfolio.adapters.incoming.mcp.dto.PortfolioSummaryDto;
import com.portfolio.adapters.incoming.mcp.mapper.PositionMcpMapper;
import com.portfolio.core.ports.incoming.GetPortfolioSummaryUseCase;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class PortfolioMcpTools {

    private final GetPortfolioSummaryUseCase getPortfolioSummaryUseCase;
    private final PositionMcpMapper mapper;
    private final McpUserIdentity identity;
    private final McpFailureTranslator failureTranslator;

    PortfolioMcpTools(
            GetPortfolioSummaryUseCase getPortfolioSummaryUseCase,
            PositionMcpMapper mapper,
            McpUserIdentity identity,
            McpFailureTranslator failureTranslator) {
        this.getPortfolioSummaryUseCase = getPortfolioSummaryUseCase;
        this.mapper = mapper;
        this.identity = identity;
        this.failureTranslator = failureTranslator;
    }

    @Tool(name = "get_portfolio_summary", description = "Aggregated portfolio totals (market value, cost, "
            + "unrealized gain/loss) across positions, derived on read from the ledger.", structuredContent = true)
    Uni<PortfolioSummaryDto> getPortfolioSummary(
            @ToolArg(description = "Only include active positions in the aggregation", required = false, defaultValue = "false") boolean activeOnly) {
        GetPortfolioSummaryUseCase.Query query = activeOnly
                ? GetPortfolioSummaryUseCase.Query.active(identity.requireUserId())
                : GetPortfolioSummaryUseCase.Query.all(identity.requireUserId());
        return failureTranslator.sanitize(getPortfolioSummaryUseCase.execute(query)
                .map(result -> switch (result) {
                    case GetPortfolioSummaryUseCase.Result.Success success -> mapper.toDto(success.summary());
                    case GetPortfolioSummaryUseCase.Result.Conflict conflict ->
                            throw new ToolCallException("CONFLICT: " + conflict.message());
                }));
    }
}

package com.portfolio.adapters.incoming.mcp;

import com.portfolio.adapters.incoming.mcp.dto.CapitalFlowsDto;
import com.portfolio.adapters.incoming.mcp.dto.DailyPositionHistoryDto;
import com.portfolio.adapters.incoming.mcp.dto.PerformanceInputsDto;
import com.portfolio.adapters.incoming.mcp.dto.PriceHistoryDto;
import com.portfolio.adapters.incoming.mcp.dto.ValuationHistoryDto;
import com.portfolio.adapters.incoming.mcp.mapper.HistoryMcpMapper;
import com.portfolio.core.ports.incoming.GetCapitalFlowsUseCase;
import com.portfolio.core.ports.incoming.GetDailyPositionHistoryUseCase;
import com.portfolio.core.ports.incoming.GetPerformanceInputsUseCase;
import com.portfolio.core.ports.incoming.GetPortfolioValuationHistoryUseCase;
import com.portfolio.core.ports.incoming.GetPriceHistoryUseCase;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
class DailyHistoryMcpTools {

    private final GetDailyPositionHistoryUseCase dailyPositionHistoryUseCase;
    private final GetPriceHistoryUseCase priceHistoryUseCase;
    private final GetPortfolioValuationHistoryUseCase valuationHistoryUseCase;
    private final GetCapitalFlowsUseCase capitalFlowsUseCase;
    private final GetPerformanceInputsUseCase performanceInputsUseCase;
    private final HistoryMcpMapper mapper;
    private final McpUserIdentity identity;
    private final McpFailureTranslator failureTranslator;

    DailyHistoryMcpTools(
            GetDailyPositionHistoryUseCase dailyPositionHistoryUseCase,
            GetPriceHistoryUseCase priceHistoryUseCase,
            GetPortfolioValuationHistoryUseCase valuationHistoryUseCase,
            GetCapitalFlowsUseCase capitalFlowsUseCase,
            GetPerformanceInputsUseCase performanceInputsUseCase,
            HistoryMcpMapper mapper,
            McpUserIdentity identity,
            McpFailureTranslator failureTranslator) {
        this.dailyPositionHistoryUseCase = dailyPositionHistoryUseCase;
        this.priceHistoryUseCase = priceHistoryUseCase;
        this.valuationHistoryUseCase = valuationHistoryUseCase;
        this.capitalFlowsUseCase = capitalFlowsUseCase;
        this.performanceInputsUseCase = performanceInputsUseCase;
        this.mapper = mapper;
        this.identity = identity;
        this.failureTranslator = failureTranslator;
    }

    @Tool(name = "get_daily_position_history", description = "Daily position snapshots (shares owned, average "
            + "cost, invested amount) over a date range.", structuredContent = true)
    Uni<DailyPositionHistoryDto> getDailyPositionHistory(
            @ToolArg(description = "Start date (ISO-8601), inclusive", required = false) LocalDate from,
            @ToolArg(description = "End date (ISO-8601), inclusive", required = false) LocalDate to,
            @ToolArg(description = "Filter by ticker symbol", required = false) String ticker) {
        return failureTranslator.sanitize(dailyPositionHistoryUseCase.execute(
                        new GetDailyPositionHistoryUseCase.Query(identity.requireUserId(), from, to, ticker))
                .map(result -> switch (result) {
                    case GetDailyPositionHistoryUseCase.Result.Success success ->
                            new DailyPositionHistoryDto(mapper.toSnapshotDtos(success.snapshots()));
                    case GetDailyPositionHistoryUseCase.Result.InvalidRequest invalid ->
                            throw new ToolCallException("INVALID_REQUEST: " + invalid.message());
                    case GetDailyPositionHistoryUseCase.Result.Conflict conflict ->
                            throw new ToolCallException("CONFLICT: " + conflict.message());
                }));
    }

    @Tool(name = "get_price_history", description = "Historical daily prices for one or more ticker symbols.", structuredContent = true)
    Uni<PriceHistoryDto> getPriceHistory(
            @ToolArg(description = "Ticker symbols to fetch price history for") @NotEmpty List<String> symbols,
            @ToolArg(description = "Start date (ISO-8601), inclusive", required = false) LocalDate from,
            @ToolArg(description = "End date (ISO-8601), inclusive", required = false) LocalDate to) {
        return failureTranslator.sanitize(priceHistoryUseCase.execute(
                        new GetPriceHistoryUseCase.Query(identity.requireUserId(), symbols, from, to))
                .map(result -> switch (result) {
                    case GetPriceHistoryUseCase.Result.Success success ->
                            new PriceHistoryDto(mapper.toPriceHistoryDtos(success.entries()));
                    case GetPriceHistoryUseCase.Result.InvalidRequest invalid ->
                            throw new ToolCallException("INVALID_REQUEST: " + invalid.message());
                }));
    }

    @Tool(name = "get_portfolio_valuation_history", description = "Daily portfolio valuation (market value, cost) "
            + "over a date range.", structuredContent = true)
    Uni<ValuationHistoryDto> getPortfolioValuationHistory(
            @ToolArg(description = "Start date (ISO-8601), inclusive", required = false) LocalDate from,
            @ToolArg(description = "End date (ISO-8601), inclusive", required = false) LocalDate to) {
        return failureTranslator.sanitize(valuationHistoryUseCase.execute(
                        new GetPortfolioValuationHistoryUseCase.Query(identity.requireUserId(), from, to))
                .map(result -> switch (result) {
                    case GetPortfolioValuationHistoryUseCase.Result.Success success ->
                            new ValuationHistoryDto(mapper.toValuationDtos(success.valuations()));
                    case GetPortfolioValuationHistoryUseCase.Result.InvalidRequest invalid ->
                            throw new ToolCallException("INVALID_REQUEST: " + invalid.message());
                    case GetPortfolioValuationHistoryUseCase.Result.Conflict conflict ->
                            throw new ToolCallException("CONFLICT: " + conflict.message());
                }));
    }

    @Tool(name = "get_capital_flows", description = "Capital flows (buys, sells, dividends) projected from the "
            + "ledger over a date range.", structuredContent = true)
    Uni<CapitalFlowsDto> getCapitalFlows(
            @ToolArg(description = "Start date (ISO-8601), inclusive", required = false) LocalDate from,
            @ToolArg(description = "End date (ISO-8601), inclusive", required = false) LocalDate to) {
        return failureTranslator.sanitize(capitalFlowsUseCase.execute(
                        new GetCapitalFlowsUseCase.Query(identity.requireUserId(), from, to))
                .map(result -> switch (result) {
                    case GetCapitalFlowsUseCase.Result.Success success ->
                            new CapitalFlowsDto(mapper.toCapitalFlowDtos(success.flows()));
                    case GetCapitalFlowsUseCase.Result.InvalidRequest invalid ->
                            throw new ToolCallException("INVALID_REQUEST: " + invalid.message());
                }));
    }

    @Tool(name = "get_performance_inputs", description = "Bundled daily valuations and capital flows for quant "
            + "performance calculations (e.g. time-weighted return) over a date range.", structuredContent = true)
    Uni<PerformanceInputsDto> getPerformanceInputs(
            @ToolArg(description = "Start date (ISO-8601), inclusive", required = false) LocalDate from,
            @ToolArg(description = "End date (ISO-8601), inclusive", required = false) LocalDate to) {
        return failureTranslator.sanitize(performanceInputsUseCase.execute(
                        new GetPerformanceInputsUseCase.Query(identity.requireUserId(), from, to))
                .map(result -> switch (result) {
                    case GetPerformanceInputsUseCase.Result.Success success -> new PerformanceInputsDto(
                            mapper.toValuationDtos(success.inputs().valuations()),
                            mapper.toCapitalFlowDtos(success.inputs().capitalFlows()),
                            success.inputs().incompleteDays());
                    case GetPerformanceInputsUseCase.Result.InvalidRequest invalid ->
                            throw new ToolCallException("INVALID_REQUEST: " + invalid.message());
                    case GetPerformanceInputsUseCase.Result.Conflict conflict ->
                            throw new ToolCallException("CONFLICT: " + conflict.message());
                }));
    }
}

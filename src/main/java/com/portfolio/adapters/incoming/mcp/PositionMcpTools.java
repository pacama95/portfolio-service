package com.portfolio.adapters.incoming.mcp;

import com.portfolio.adapters.incoming.mcp.dto.PositionDto;
import com.portfolio.adapters.incoming.mcp.dto.PositionListDto;
import com.portfolio.adapters.incoming.mcp.mapper.PositionMcpMapper;
import com.portfolio.core.model.Currency;
import com.portfolio.core.ports.incoming.ClearManualPriceUseCase;
import com.portfolio.core.ports.incoming.GetPositionByTickerUseCase;
import com.portfolio.core.ports.incoming.GetPositionsUseCase;
import com.portfolio.core.ports.incoming.UpdateMarketPriceUseCase;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@ApplicationScoped
class PositionMcpTools {

    private final GetPositionsUseCase getPositionsUseCase;
    private final GetPositionByTickerUseCase getPositionByTickerUseCase;
    private final UpdateMarketPriceUseCase updateMarketPriceUseCase;
    private final ClearManualPriceUseCase clearManualPriceUseCase;
    private final PositionMcpMapper mapper;
    private final McpUserIdentity identity;
    private final McpFailureTranslator failureTranslator;

    PositionMcpTools(
            GetPositionsUseCase getPositionsUseCase,
            GetPositionByTickerUseCase getPositionByTickerUseCase,
            UpdateMarketPriceUseCase updateMarketPriceUseCase,
            ClearManualPriceUseCase clearManualPriceUseCase,
            PositionMcpMapper mapper,
            McpUserIdentity identity,
            McpFailureTranslator failureTranslator) {
        this.getPositionsUseCase = getPositionsUseCase;
        this.getPositionByTickerUseCase = getPositionByTickerUseCase;
        this.updateMarketPriceUseCase = updateMarketPriceUseCase;
        this.clearManualPriceUseCase = clearManualPriceUseCase;
        this.mapper = mapper;
        this.identity = identity;
        this.failureTranslator = failureTranslator;
    }

    @Tool(name = "list_positions", description = "Lists the caller's positions, derived on read from the "
            + "transaction ledger.", structuredContent = true)
    Uni<PositionListDto> listPositions(
            @ToolArg(description = "Only include positions with a non-zero holding", required = false, defaultValue = "false") boolean activeOnly) {
        return failureTranslator.sanitize(getPositionsUseCase.execute(new GetPositionsUseCase.Query(
                        identity.requireUserId(), activeOnly))
                .map(result -> switch (result) {
                    case GetPositionsUseCase.Result.Success success ->
                            PositionListDto.of(success.positions().stream().map(mapper::toDto).toList());
                    case GetPositionsUseCase.Result.Conflict conflict ->
                            throw new ToolCallException("CONFLICT: " + conflict.message());
                }));
    }

    @Tool(name = "get_position", description = "Fetches the position for a single ticker.", structuredContent = true)
    Uni<PositionDto> getPosition(
            @ToolArg(description = "Ticker symbol") @NotBlank String ticker,
            @ToolArg(description = "Whether to include the latest market price (set false to check holdings/existence only)",
                    required = false, defaultValue = "true") boolean includeMarketPrice) {
        GetPositionByTickerUseCase.Query query = includeMarketPrice
                ? new GetPositionByTickerUseCase.Query(identity.requireUserId(), ticker)
                : GetPositionByTickerUseCase.Query.withoutMarketPrice(identity.requireUserId(), ticker);
        return failureTranslator.sanitize(getPositionByTickerUseCase.execute(query)
                .map(result -> switch (result) {
                    case GetPositionByTickerUseCase.Result.Success success -> mapper.toDto(success.position());
                    case GetPositionByTickerUseCase.Result.NotFound notFound ->
                            throw new ToolCallException("NOT_FOUND: position for ticker " + notFound.ticker() + " not found");
                }));
    }

    @Tool(name = "set_position_price", description = "Manually overrides the latest market price for a ticker, "
            + "until cleared or refreshed by provider pricing.", structuredContent = true)
    Uni<PositionDto> setPositionPrice(
            @ToolArg(description = "Ticker symbol") @NotBlank String ticker,
            @ToolArg(description = "New price; must be zero or positive") @NotNull @DecimalMin("0.0") BigDecimal price,
            @ToolArg(description = "ISO currency code of the price", required = false) Currency currency) {
        return failureTranslator.sanitize(updateMarketPriceUseCase.execute(
                        new UpdateMarketPriceUseCase.Command(identity.requireUserId(), ticker, price, currency))
                .map(result -> switch (result) {
                    case UpdateMarketPriceUseCase.Result.Success success -> mapper.toDto(success.position());
                    case UpdateMarketPriceUseCase.Result.NotFound notFound ->
                            throw new ToolCallException("NOT_FOUND: position for ticker " + notFound.ticker() + " not found");
                    case UpdateMarketPriceUseCase.Result.InvalidRequest invalid ->
                            throw new ToolCallException("INVALID_REQUEST: " + invalid.message());
                }));
    }

    @Tool(name = "clear_position_price", description = "Clears a manually set market price for a ticker, "
            + "reverting to provider pricing.", structuredContent = true)
    Uni<PositionDto> clearPositionPrice(@ToolArg(description = "Ticker symbol") @NotBlank String ticker) {
        return failureTranslator.sanitize(clearManualPriceUseCase.execute(
                        new ClearManualPriceUseCase.Command(identity.requireUserId(), ticker))
                .map(result -> switch (result) {
                    case ClearManualPriceUseCase.Result.Success success -> mapper.toDto(success.position());
                    case ClearManualPriceUseCase.Result.NotFound notFound ->
                            throw new ToolCallException("NOT_FOUND: position for ticker " + notFound.ticker() + " not found");
                }));
    }
}

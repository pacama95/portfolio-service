package com.portfolio.adapters.incoming.mcp;

import com.portfolio.adapters.incoming.mcp.dto.PositionDto;
import com.portfolio.adapters.incoming.mcp.dto.PositionListDto;
import com.portfolio.adapters.incoming.mcp.mapper.PositionMcpMapper;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Position;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.ClearManualPriceUseCase;
import com.portfolio.core.ports.incoming.GetPositionByTickerUseCase;
import com.portfolio.core.ports.incoming.GetPositionsUseCase;
import com.portfolio.core.ports.incoming.UpdateMarketPriceUseCase;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PositionMcpToolsTest {

    private static final UserId USER_ID = UserId.of("user-1");

    private final GetPositionsUseCase getPositionsUseCase = Mockito.mock(GetPositionsUseCase.class);
    private final GetPositionByTickerUseCase getPositionByTickerUseCase = Mockito.mock(GetPositionByTickerUseCase.class);
    private final UpdateMarketPriceUseCase updateMarketPriceUseCase = Mockito.mock(UpdateMarketPriceUseCase.class);
    private final ClearManualPriceUseCase clearManualPriceUseCase = Mockito.mock(ClearManualPriceUseCase.class);
    private final PositionMcpMapper mapper = Mockito.mock(PositionMcpMapper.class);
    private final McpUserIdentity identity = Mockito.mock(McpUserIdentity.class);
    private final McpFailureTranslator failureTranslator = Mockito.mock(McpFailureTranslator.class);

    private PositionMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new PositionMcpTools(
                getPositionsUseCase, getPositionByTickerUseCase, updateMarketPriceUseCase,
                clearManualPriceUseCase, mapper, identity, failureTranslator);
        when(identity.requireUserId()).thenReturn(USER_ID);
        when(failureTranslator.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Position samplePosition() {
        return new Position("AAPL", "Apple Inc.", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.ZERO, Currency.USD, BigDecimal.TEN, LocalDate.of(2024, 1, 1), true, "NASDAQ", "US");
    }

    private static PositionDto sampleDto() {
        return new PositionDto("AAPL", "Apple Inc.", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, Currency.USD, LocalDate.of(2024, 1, 1), true, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, "NASDAQ", "US");
    }

    @Test
    void givenActiveOnlyFalse_whenListPositions_thenReturnsAllMappedPositions() {
        Position position = samplePosition();
        PositionDto dto = sampleDto();
        when(getPositionsUseCase.execute(new GetPositionsUseCase.Query(USER_ID, false)))
                .thenReturn(Uni.createFrom().item(new GetPositionsUseCase.Result.Success(List.of(position))));
        when(mapper.toDto(position)).thenReturn(dto);

        PositionListDto result = tools.listPositions(false)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(1, result.count());
        assertEquals(dto, result.positions().get(0));
    }

    @Test
    void givenActiveOnlyTrue_whenListPositions_thenQueriesActiveOnly() {
        when(getPositionsUseCase.execute(new GetPositionsUseCase.Query(USER_ID, true)))
                .thenReturn(Uni.createFrom().item(new GetPositionsUseCase.Result.Success(List.of())));

        PositionListDto result = tools.listPositions(true)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(0, result.count());
    }

    @Test
    void givenConflict_whenListPositions_thenFailsWithConflictCode() {
        when(getPositionsUseCase.execute(new GetPositionsUseCase.Query(USER_ID, false)))
                .thenReturn(Uni.createFrom().item(new GetPositionsUseCase.Result.Conflict("bad ledger state")));

        Throwable failure = tools.listPositions(false)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("CONFLICT: bad ledger state", failure.getMessage());
    }

    @Test
    void givenIncludeMarketPriceTrue_whenGetPosition_thenUsesDefaultQuery() {
        Position position = samplePosition();
        PositionDto dto = sampleDto();
        when(getPositionByTickerUseCase.execute(new GetPositionByTickerUseCase.Query(USER_ID, "AAPL")))
                .thenReturn(Uni.createFrom().item(new GetPositionByTickerUseCase.Result.Success(position)));
        when(mapper.toDto(position)).thenReturn(dto);

        PositionDto result = tools.getPosition("AAPL", true)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(dto, result);
    }

    @Test
    void givenIncludeMarketPriceFalse_whenGetPosition_thenUsesWithoutMarketPriceQuery() {
        Position position = samplePosition();
        PositionDto dto = sampleDto();
        when(getPositionByTickerUseCase.execute(GetPositionByTickerUseCase.Query.withoutMarketPrice(USER_ID, "AAPL")))
                .thenReturn(Uni.createFrom().item(new GetPositionByTickerUseCase.Result.Success(position)));
        when(mapper.toDto(position)).thenReturn(dto);

        PositionDto result = tools.getPosition("AAPL", false)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(dto, result);
    }

    @Test
    void givenMissingTicker_whenGetPosition_thenFailsWithNotFoundCode() {
        when(getPositionByTickerUseCase.execute(new GetPositionByTickerUseCase.Query(USER_ID, "ZZZZ")))
                .thenReturn(Uni.createFrom().item(new GetPositionByTickerUseCase.Result.NotFound("ZZZZ")));

        Throwable failure = tools.getPosition("ZZZZ", true)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure.getMessage().startsWith("NOT_FOUND"));
        assertTrue(failure.getMessage().contains("ZZZZ"));
    }

    @Test
    void givenValidPrice_whenSetPositionPrice_thenReturnsMappedDto() {
        Position position = samplePosition();
        PositionDto dto = sampleDto();
        when(updateMarketPriceUseCase.execute(
                        new UpdateMarketPriceUseCase.Command(USER_ID, "AAPL", BigDecimal.TEN, Currency.USD)))
                .thenReturn(Uni.createFrom().item(new UpdateMarketPriceUseCase.Result.Success(position)));
        when(mapper.toDto(position)).thenReturn(dto);

        PositionDto result = tools.setPositionPrice("AAPL", BigDecimal.TEN, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(dto, result);
    }

    @Test
    void givenMissingTicker_whenSetPositionPrice_thenFailsWithNotFoundCode() {
        when(updateMarketPriceUseCase.execute(
                        new UpdateMarketPriceUseCase.Command(USER_ID, "ZZZZ", BigDecimal.TEN, null)))
                .thenReturn(Uni.createFrom().item(new UpdateMarketPriceUseCase.Result.NotFound("ZZZZ")));

        Throwable failure = tools.setPositionPrice("ZZZZ", BigDecimal.TEN, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure.getMessage().startsWith("NOT_FOUND"));
    }

    @Test
    void givenInvalidPrice_whenSetPositionPrice_thenFailsWithInvalidRequestCode() {
        when(updateMarketPriceUseCase.execute(
                        new UpdateMarketPriceUseCase.Command(USER_ID, "AAPL", BigDecimal.TEN, null)))
                .thenReturn(Uni.createFrom().item(new UpdateMarketPriceUseCase.Result.InvalidRequest("bad price")));

        Throwable failure = tools.setPositionPrice("AAPL", BigDecimal.TEN, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("INVALID_REQUEST: bad price", failure.getMessage());
    }

    @Test
    void givenExistingTicker_whenClearPositionPrice_thenReturnsMappedDto() {
        Position position = samplePosition();
        PositionDto dto = sampleDto();
        when(clearManualPriceUseCase.execute(new ClearManualPriceUseCase.Command(USER_ID, "AAPL")))
                .thenReturn(Uni.createFrom().item(new ClearManualPriceUseCase.Result.Success(position)));
        when(mapper.toDto(position)).thenReturn(dto);

        PositionDto result = tools.clearPositionPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(dto, result);
    }

    @Test
    void givenMissingTicker_whenClearPositionPrice_thenFailsWithNotFoundCode() {
        when(clearManualPriceUseCase.execute(new ClearManualPriceUseCase.Command(USER_ID, "ZZZZ")))
                .thenReturn(Uni.createFrom().item(new ClearManualPriceUseCase.Result.NotFound("ZZZZ")));

        Throwable failure = tools.clearPositionPrice("ZZZZ")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure.getMessage().startsWith("NOT_FOUND"));
    }
}

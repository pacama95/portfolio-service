package com.portfolio.adapters.incoming.rest;

import com.portfolio.adapters.incoming.rest.dto.PositionResponse;
import com.portfolio.adapters.incoming.rest.dto.UpdateMarketDataRequest;
import com.portfolio.adapters.incoming.rest.mapper.PositionRestMapper;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Position;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPositionByTickerUseCase;
import com.portfolio.core.ports.incoming.GetPositionsUseCase;
import com.portfolio.core.ports.incoming.UpdateMarketPriceUseCase;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class PositionControllerTest {

    private static final UserId USER_ID = UserId.of("user-1");

    private final GetPositionsUseCase getPositionsUseCase = Mockito.mock(GetPositionsUseCase.class);
    private final GetPositionByTickerUseCase getPositionByTickerUseCase = Mockito.mock(GetPositionByTickerUseCase.class);
    private final UpdateMarketPriceUseCase updateMarketPriceUseCase = Mockito.mock(UpdateMarketPriceUseCase.class);
    private final PositionRestMapper mapper = Mockito.mock(PositionRestMapper.class);
    private final UserContext userContext = Mockito.mock(UserContext.class);

    private PositionController controller;

    @BeforeEach
    void setUp() {
        controller = new PositionController(
                getPositionsUseCase,
                getPositionByTickerUseCase,
                updateMarketPriceUseCase,
                mapper,
                userContext);
        when(userContext.requireUserId()).thenReturn(USER_ID);
    }

    @Test
    void givenPositions_whenListAll_thenReturns200() {
        Position position = samplePosition();
        PositionResponse responseBody = samplePositionResponse(position);

        when(getPositionsUseCase.execute(GetPositionsUseCase.Query.all(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPositionsUseCase.Result.Success(List.of(position))));
        when(mapper.toResponse(position)).thenReturn(responseBody);

        Response response = controller.listAll()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(List.of(responseBody), response.getEntity());
    }

    @Test
    void givenActivePositions_whenListActive_thenReturns200() {
        Position position = samplePosition();
        PositionResponse responseBody = samplePositionResponse(position);

        when(getPositionsUseCase.execute(GetPositionsUseCase.Query.active(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPositionsUseCase.Result.Success(List.of(position))));
        when(mapper.toResponse(position)).thenReturn(responseBody);

        Response response = controller.listActive()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(List.of(responseBody), response.getEntity());
    }

    @Test
    void givenInconsistentLedger_whenListAll_thenReturns409() {
        when(getPositionsUseCase.execute(GetPositionsUseCase.Query.all(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPositionsUseCase.Result.Conflict("Oversell for AAPL")));

        Response response = controller.listAll()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
    }

    @Test
    void givenInconsistentLedger_whenCount_thenThrowsConflictResponse() {
        when(getPositionsUseCase.execute(GetPositionsUseCase.Query.all(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPositionsUseCase.Result.Conflict("Oversell for AAPL")));

        WebApplicationException ex = (WebApplicationException) controller.count()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(WebApplicationException.class)
                .getFailure();

        assertEquals(Response.Status.CONFLICT.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    void givenExistingPosition_whenGetByTicker_thenReturns200() {
        Position position = samplePosition();
        PositionResponse responseBody = samplePositionResponse(position);

        when(getPositionByTickerUseCase.execute(new GetPositionByTickerUseCase.Query(USER_ID, "AAPL")))
                .thenReturn(Uni.createFrom().item(new GetPositionByTickerUseCase.Result.Success(position)));
        when(mapper.toResponse(position)).thenReturn(responseBody);

        Response response = controller.getByTicker("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(responseBody, response.getEntity());
    }

    @Test
    void givenMissingPosition_whenGetByTicker_thenReturns404() {
        when(getPositionByTickerUseCase.execute(new GetPositionByTickerUseCase.Query(USER_ID, "MSFT")))
                .thenReturn(Uni.createFrom().item(new GetPositionByTickerUseCase.Result.NotFound("MSFT")));

        Response response = controller.getByTicker("MSFT")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void givenExistingPosition_whenExists_thenReturnsTrue() {
        Position position = samplePosition();

        when(getPositionByTickerUseCase.execute(new GetPositionByTickerUseCase.Query(USER_ID, "AAPL")))
                .thenReturn(Uni.createFrom().item(new GetPositionByTickerUseCase.Result.Success(position)));

        Boolean exists = controller.exists("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertTrue(exists);
    }

    @Test
    void givenMissingPosition_whenExists_thenReturnsFalse() {
        when(getPositionByTickerUseCase.execute(new GetPositionByTickerUseCase.Query(USER_ID, "MSFT")))
                .thenReturn(Uni.createFrom().item(new GetPositionByTickerUseCase.Result.NotFound("MSFT")));

        Boolean exists = controller.exists("MSFT")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertFalse(exists);
    }

    @Test
    void givenPositions_whenCount_thenReturnsLong() {
        Position first = samplePosition();
        Position second = samplePosition();

        when(getPositionsUseCase.execute(GetPositionsUseCase.Query.all(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPositionsUseCase.Result.Success(List.of(first, second))));

        Long count = controller.count()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(2L, count);
    }

    @Test
    void givenActivePositions_whenCountActive_thenReturnsLong() {
        Position position = samplePosition();

        when(getPositionsUseCase.execute(GetPositionsUseCase.Query.active(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPositionsUseCase.Result.Success(List.of(position))));

        Long count = controller.countActive()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(1L, count);
    }

    @Test
    void givenValidPriceUpdate_whenUpdatePrice_thenReturns200() {
        Position position = samplePosition();
        PositionResponse responseBody = samplePositionResponse(position);
        UpdateMarketDataRequest request = new UpdateMarketDataRequest(new BigDecimal("155"), Currency.USD);
        UpdateMarketPriceUseCase.Command command = new UpdateMarketPriceUseCase.Command(
                USER_ID, "AAPL", request.price(), request.currency());

        when(updateMarketPriceUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new UpdateMarketPriceUseCase.Result.Success(position)));
        when(mapper.toResponse(position)).thenReturn(responseBody);

        Response response = controller.updatePrice("AAPL", request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(responseBody, response.getEntity());
    }

    @Test
    void givenMissingPosition_whenUpdatePrice_thenReturns404() {
        UpdateMarketDataRequest request = new UpdateMarketDataRequest(new BigDecimal("155"), Currency.USD);
        UpdateMarketPriceUseCase.Command command = new UpdateMarketPriceUseCase.Command(
                USER_ID, "MSFT", request.price(), request.currency());

        when(updateMarketPriceUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new UpdateMarketPriceUseCase.Result.NotFound("MSFT")));

        Response response = controller.updatePrice("MSFT", request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void givenInvalidPriceUpdate_whenUpdatePrice_thenReturns400() {
        UpdateMarketDataRequest request = new UpdateMarketDataRequest(new BigDecimal("-1"), Currency.USD);
        UpdateMarketPriceUseCase.Command command = new UpdateMarketPriceUseCase.Command(
                USER_ID, "AAPL", request.price(), request.currency());
        UpdateMarketPriceUseCase.Result.InvalidRequest invalid =
                new UpdateMarketPriceUseCase.Result.InvalidRequest("price must be positive");

        when(updateMarketPriceUseCase.execute(command)).thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.updatePrice("AAPL", request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(invalid, response.getEntity());
    }

    private static Position samplePosition() {
        return new Position(
                "AAPL",
                "Apple Inc.",
                new BigDecimal("10"),
                new BigDecimal("100"),
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                Currency.USD,
                new BigDecimal("150"),
                LocalDate.of(2024, 1, 1),
                true,
                "NASDAQ",
                "US");
    }

    private static PositionResponse samplePositionResponse(Position position) {
        return new PositionResponse(
                position.ticker(),
                position.companyName(),
                position.sharesOwned(),
                position.averageCostPerShare(),
                position.latestMarketPrice(),
                position.totalInvestedAmount(),
                position.currency(),
                position.firstPurchaseDate(),
                position.active(),
                position.marketValue(),
                position.unrealizedGainLoss(),
                position.unrealizedGainLossPercentage(),
                position.exchange(),
                position.country());
    }
}

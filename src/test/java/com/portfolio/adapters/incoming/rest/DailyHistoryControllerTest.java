package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.model.CapitalFlowEntry;
import com.portfolio.core.model.CapitalFlowKind;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyPositionSnapshot;
import com.portfolio.core.model.DailyValuation;
import com.portfolio.core.model.PerformanceInputs;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetCapitalFlowsUseCase;
import com.portfolio.core.ports.incoming.GetDailyPositionHistoryUseCase;
import com.portfolio.core.ports.incoming.GetPerformanceInputsUseCase;
import com.portfolio.core.ports.incoming.GetPortfolioValuationHistoryUseCase;
import com.portfolio.core.ports.incoming.GetPriceHistoryUseCase;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyHistoryControllerTest {

    private static final UserId USER_ID = UserId.of("user-1");

    private final GetDailyPositionHistoryUseCase dailyPositionHistoryUseCase =
            Mockito.mock(GetDailyPositionHistoryUseCase.class);
    private final GetPriceHistoryUseCase priceHistoryUseCase = Mockito.mock(GetPriceHistoryUseCase.class);
    private final GetPortfolioValuationHistoryUseCase valuationHistoryUseCase =
            Mockito.mock(GetPortfolioValuationHistoryUseCase.class);
    private final GetCapitalFlowsUseCase capitalFlowsUseCase = Mockito.mock(GetCapitalFlowsUseCase.class);
    private final GetPerformanceInputsUseCase performanceInputsUseCase =
            Mockito.mock(GetPerformanceInputsUseCase.class);
    private final UserContext userContext = Mockito.mock(UserContext.class);

    private DailyHistoryController controller;

    @BeforeEach
    void setUp() {
        controller = new DailyHistoryController(
                dailyPositionHistoryUseCase,
                priceHistoryUseCase,
                valuationHistoryUseCase,
                capitalFlowsUseCase,
                performanceInputsUseCase,
                userContext);
        when(userContext.requireUserId()).thenReturn(USER_ID);
    }

    @Test
    void givenValidRange_whenPositions_thenReturns200() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 31);
        List<DailyPositionSnapshot> snapshots = List.of(sampleSnapshot());

        when(dailyPositionHistoryUseCase.execute(
                new GetDailyPositionHistoryUseCase.Query(USER_ID, from, to, "AAPL")))
                .thenReturn(Uni.createFrom().item(new GetDailyPositionHistoryUseCase.Result.Success(snapshots)));

        Response response = controller.positions(from, to, "AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(snapshots, response.getEntity());
    }

    @Test
    void givenInvalidRange_whenPositions_thenReturns400() {
        LocalDate from = LocalDate.of(2024, 2, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        GetDailyPositionHistoryUseCase.Result.InvalidRequest invalid =
                new GetDailyPositionHistoryUseCase.Result.InvalidRequest("from must be before to");

        when(dailyPositionHistoryUseCase.execute(
                new GetDailyPositionHistoryUseCase.Query(USER_ID, from, to, null)))
                .thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.positions(from, to, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(invalid, response.getEntity());
    }

    @Test
    void givenInconsistentLedger_whenPositions_thenReturns409() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 31);

        when(dailyPositionHistoryUseCase.execute(
                new GetDailyPositionHistoryUseCase.Query(USER_ID, from, to, null)))
                .thenReturn(Uni.createFrom().item(
                        new GetDailyPositionHistoryUseCase.Result.Conflict("Oversell for AAPL")));

        Response response = controller.positions(from, to, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
    }

    @Test
    void givenValidSymbolsCsv_whenPrices_thenReturns200AndParsesSymbols() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 31);
        List<PriceHistoryEntry> entries = List.of(samplePriceEntry());
        ArgumentCaptor<GetPriceHistoryUseCase.Query> queryCaptor =
                ArgumentCaptor.forClass(GetPriceHistoryUseCase.Query.class);

        when(priceHistoryUseCase.execute(any())).thenReturn(
                Uni.createFrom().item(new GetPriceHistoryUseCase.Result.Success(entries)));

        Response response = controller.prices("aapl, msft", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        verify(priceHistoryUseCase).execute(queryCaptor.capture());
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(entries, response.getEntity());
        assertEquals(USER_ID, queryCaptor.getValue().userId());
        assertEquals(List.of("AAPL", "MSFT"), queryCaptor.getValue().symbols());
        assertEquals(from, queryCaptor.getValue().from());
        assertEquals(to, queryCaptor.getValue().to());
    }

    @Test
    void givenInvalidRange_whenPrices_thenReturns400() {
        LocalDate from = LocalDate.of(2024, 2, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        GetPriceHistoryUseCase.Result.InvalidRequest invalid =
                new GetPriceHistoryUseCase.Result.InvalidRequest("invalid range");

        when(priceHistoryUseCase.execute(any())).thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.prices("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(invalid, response.getEntity());
    }

    @Test
    void givenValidRange_whenValuation_thenReturns200() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 31);
        List<DailyValuation> valuations = List.of(sampleValuation());

        when(valuationHistoryUseCase.execute(new GetPortfolioValuationHistoryUseCase.Query(USER_ID, from, to)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioValuationHistoryUseCase.Result.Success(valuations)));

        Response response = controller.valuation(from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(valuations, response.getEntity());
    }

    @Test
    void givenInvalidRange_whenValuation_thenReturns400() {
        LocalDate from = LocalDate.of(2024, 2, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        GetPortfolioValuationHistoryUseCase.Result.InvalidRequest invalid =
                new GetPortfolioValuationHistoryUseCase.Result.InvalidRequest("invalid range");

        when(valuationHistoryUseCase.execute(new GetPortfolioValuationHistoryUseCase.Query(USER_ID, from, to)))
                .thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.valuation(from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(invalid, response.getEntity());
    }

    @Test
    void givenValidRange_whenCapitalFlows_thenReturns200WithFlowsKey() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 31);
        List<CapitalFlowEntry> flows = List.of(sampleCapitalFlow());

        when(capitalFlowsUseCase.execute(new GetCapitalFlowsUseCase.Query(USER_ID, from, to)))
                .thenReturn(Uni.createFrom().item(new GetCapitalFlowsUseCase.Result.Success(flows)));

        Response response = controller.capitalFlows(from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(Map.of("flows", flows), response.getEntity());
    }

    @Test
    void givenInvalidRange_whenCapitalFlows_thenReturns400() {
        LocalDate from = LocalDate.of(2024, 2, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        GetCapitalFlowsUseCase.Result.InvalidRequest invalid =
                new GetCapitalFlowsUseCase.Result.InvalidRequest("invalid range");

        when(capitalFlowsUseCase.execute(new GetCapitalFlowsUseCase.Query(USER_ID, from, to)))
                .thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.capitalFlows(from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(invalid, response.getEntity());
    }

    @Test
    void givenValidRange_whenPerformanceInputs_thenReturns200() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 31);
        PerformanceInputs inputs = new PerformanceInputs(
                List.of(sampleValuation()),
                List.of(sampleCapitalFlow()),
                0);

        when(performanceInputsUseCase.execute(new GetPerformanceInputsUseCase.Query(USER_ID, from, to)))
                .thenReturn(Uni.createFrom().item(new GetPerformanceInputsUseCase.Result.Success(inputs)));

        Response response = controller.performanceInputs(from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(inputs, response.getEntity());
    }

    @Test
    void givenInvalidRange_whenPerformanceInputs_thenReturns400() {
        LocalDate from = LocalDate.of(2024, 2, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        GetPerformanceInputsUseCase.Result.InvalidRequest invalid =
                new GetPerformanceInputsUseCase.Result.InvalidRequest("invalid range");

        when(performanceInputsUseCase.execute(new GetPerformanceInputsUseCase.Query(USER_ID, from, to)))
                .thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.performanceInputs(from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(invalid, response.getEntity());
    }

    private static DailyPositionSnapshot sampleSnapshot() {
        return new DailyPositionSnapshot(
                "AAPL",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("10"),
                new BigDecimal("100"),
                new BigDecimal("1000"),
                Currency.USD);
    }

    private static PriceHistoryEntry samplePriceEntry() {
        return new PriceHistoryEntry(
                "AAPL",
                LocalDate.of(2024, 1, 15),
                new BigDecimal("150"),
                new BigDecimal("150"),
                Currency.USD,
                OffsetDateTime.parse("2024-01-15T00:00:00Z"));
    }

    private static DailyValuation sampleValuation() {
        return new DailyValuation(
                LocalDate.of(2024, 1, 15),
                Currency.USD,
                new BigDecimal("1500"),
                new BigDecimal("1000"),
                true);
    }

    private static CapitalFlowEntry sampleCapitalFlow() {
        return new CapitalFlowEntry(
                UUID.randomUUID(),
                "AAPL",
                LocalDate.of(2024, 1, 10),
                CapitalFlowKind.BUY,
                Currency.USD,
                new BigDecimal("1000"),
                BigDecimal.ZERO);
    }
}

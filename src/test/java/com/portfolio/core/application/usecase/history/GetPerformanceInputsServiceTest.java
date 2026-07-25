package com.portfolio.core.application.usecase.history;

import com.portfolio.core.model.CapitalFlowEntry;
import com.portfolio.core.model.CapitalFlowKind;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyValuation;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetCapitalFlowsUseCase;
import com.portfolio.core.ports.incoming.GetPerformanceInputsUseCase;
import com.portfolio.core.ports.incoming.GetPortfolioValuationHistoryUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GetPerformanceInputsServiceTest {

    private static final UserId USER = UserId.of("user-1");
    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 31);

    private final GetPortfolioValuationHistoryUseCase valuationHistoryUseCase =
            Mockito.mock(GetPortfolioValuationHistoryUseCase.class);
    private final GetCapitalFlowsUseCase capitalFlowsUseCase = Mockito.mock(GetCapitalFlowsUseCase.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private GetPerformanceInputsService service;

    @BeforeEach
    void setUp() {
        service = new GetPerformanceInputsService(
                valuationHistoryUseCase, capitalFlowsUseCase, marketDataPort, "USD", 3650);
    }

    @Test
    void givenRangeExceedsMax_whenExecute_thenInvalidRequest() {
        GetPerformanceInputsService strict = new GetPerformanceInputsService(
                valuationHistoryUseCase, capitalFlowsUseCase, marketDataPort, "USD", 5);

        GetPerformanceInputsUseCase.Result result = strict.execute(
                new GetPerformanceInputsUseCase.Query(USER, FROM, LocalDate.of(2024, 1, 20)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPerformanceInputsUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNullTo_whenExecute_thenInvalidRequest() {
        GetPerformanceInputsUseCase.Result result = service.execute(
                new GetPerformanceInputsUseCase.Query(USER, FROM, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPerformanceInputsUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenSameFromAndTo_whenExecute_thenReturnsPerformanceInputs() {
        List<DailyValuation> valuations = List.of(valuation(FROM, true));
        List<CapitalFlowEntry> flows = List.of(new CapitalFlowEntry(
                UUID.randomUUID(),
                "AAPL",
                FROM,
                CapitalFlowKind.BUY,
                Currency.USD,
                new BigDecimal("-1000"),
                BigDecimal.ZERO));
        when(valuationHistoryUseCase.execute(any(GetPortfolioValuationHistoryUseCase.Query.class)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioValuationHistoryUseCase.Result.Success(valuations)));
        when(capitalFlowsUseCase.execute(any(GetCapitalFlowsUseCase.Query.class)))
                .thenReturn(Uni.createFrom().item(new GetCapitalFlowsUseCase.Result.Success(flows)));

        GetPerformanceInputsUseCase.Result result = service.execute(
                new GetPerformanceInputsUseCase.Query(USER, FROM, FROM))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPerformanceInputsUseCase.Result.Success.class, result);
    }

    @Test
    void givenNullFrom_whenExecute_thenInvalidRequest() {
        GetPerformanceInputsUseCase.Result result = service.execute(
                new GetPerformanceInputsUseCase.Query(USER, null, TO))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPerformanceInputsUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenFromAfterTo_whenExecute_thenInvalidRequest() {
        GetPerformanceInputsUseCase.Result result = service.execute(
                new GetPerformanceInputsUseCase.Query(
                        USER, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 1, 1)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPerformanceInputsUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenValuationInvalidRequest_whenExecute_thenPropagatesInvalidRequest() {
        when(valuationHistoryUseCase.execute(any(GetPortfolioValuationHistoryUseCase.Query.class)))
                .thenReturn(Uni.createFrom().item(
                        new GetPortfolioValuationHistoryUseCase.Result.InvalidRequest("bad dates")));
        when(capitalFlowsUseCase.execute(any(GetCapitalFlowsUseCase.Query.class)))
                .thenReturn(Uni.createFrom().item(new GetCapitalFlowsUseCase.Result.Success(List.of())));

        GetPerformanceInputsUseCase.Result result = service.execute(
                new GetPerformanceInputsUseCase.Query(USER, FROM, TO))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPerformanceInputsUseCase.Result.InvalidRequest invalid =
                assertInstanceOf(GetPerformanceInputsUseCase.Result.InvalidRequest.class, result);
        assertEquals("bad dates", invalid.message());
    }

    @Test
    void givenValuationConflict_whenExecute_thenPropagatesConflict() {
        when(valuationHistoryUseCase.execute(any(GetPortfolioValuationHistoryUseCase.Query.class)))
                .thenReturn(Uni.createFrom().item(
                        new GetPortfolioValuationHistoryUseCase.Result.Conflict("Oversell for AAPL")));

        GetPerformanceInputsUseCase.Result result = service.execute(
                new GetPerformanceInputsUseCase.Query(USER, FROM, TO))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPerformanceInputsUseCase.Result.Conflict conflict =
                assertInstanceOf(GetPerformanceInputsUseCase.Result.Conflict.class, result);
        assertEquals("Oversell for AAPL", conflict.message());
    }

    @Test
    void givenCapitalFlowsInvalidRequest_whenExecute_thenPropagatesInvalidRequest() {
        when(valuationHistoryUseCase.execute(any(GetPortfolioValuationHistoryUseCase.Query.class)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioValuationHistoryUseCase.Result.Success(List.of())));
        when(capitalFlowsUseCase.execute(any(GetCapitalFlowsUseCase.Query.class)))
                .thenReturn(Uni.createFrom().item(
                        new GetCapitalFlowsUseCase.Result.InvalidRequest("bad flows")));

        GetPerformanceInputsUseCase.Result result = service.execute(
                new GetPerformanceInputsUseCase.Query(USER, FROM, TO))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPerformanceInputsUseCase.Result.InvalidRequest invalid =
                assertInstanceOf(GetPerformanceInputsUseCase.Result.InvalidRequest.class, result);
        assertEquals("bad flows", invalid.message());
    }

    @Test
    void givenValuationsWithIncompleteDays_whenExecute_thenReturnsIncompleteCount() {
        List<DailyValuation> valuations = List.of(
                valuation(FROM, true),
                valuation(FROM.plusDays(1), false),
                valuation(FROM.plusDays(2), false));
        List<CapitalFlowEntry> flows = List.of(new CapitalFlowEntry(
                UUID.randomUUID(),
                "AAPL",
                FROM,
                CapitalFlowKind.BUY,
                Currency.USD,
                new BigDecimal("-1000"),
                BigDecimal.ZERO));
        when(valuationHistoryUseCase.execute(any(GetPortfolioValuationHistoryUseCase.Query.class)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioValuationHistoryUseCase.Result.Success(valuations)));
        when(capitalFlowsUseCase.execute(any(GetCapitalFlowsUseCase.Query.class)))
                .thenReturn(Uni.createFrom().item(new GetCapitalFlowsUseCase.Result.Success(flows)));

        GetPerformanceInputsUseCase.Result result = service.execute(
                new GetPerformanceInputsUseCase.Query(USER, FROM, TO))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPerformanceInputsUseCase.Result.Success success =
                assertInstanceOf(GetPerformanceInputsUseCase.Result.Success.class, result);
        assertEquals(2, success.inputs().incompleteDays());
        assertEquals(valuations, success.inputs().valuations());
        assertEquals(flows, success.inputs().capitalFlows());
    }

    @Test
    void givenForeignCurrencyFlow_whenExecute_thenConvertsFlowToBaseCurrency() {
        List<DailyValuation> valuations = List.of(valuation(FROM, true));
        LocalDate flowDate = FROM.plusDays(2);
        List<CapitalFlowEntry> flows = List.of(new CapitalFlowEntry(
                UUID.randomUUID(),
                "ASML",
                flowDate,
                CapitalFlowKind.BUY,
                Currency.EUR,
                new BigDecimal("-1000"),
                BigDecimal.ZERO));
        when(valuationHistoryUseCase.execute(any(GetPortfolioValuationHistoryUseCase.Query.class)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioValuationHistoryUseCase.Result.Success(valuations)));
        when(capitalFlowsUseCase.execute(any(GetCapitalFlowsUseCase.Query.class)))
                .thenReturn(Uni.createFrom().item(new GetCapitalFlowsUseCase.Result.Success(flows)));
        when(marketDataPort.getFxHistory(Currency.EUR, Currency.USD, FROM.minusDays(7), TO))
                .thenReturn(Uni.createFrom().item(List.of(
                        new FxRateEntry(Currency.EUR, Currency.USD, flowDate, new BigDecimal("1.10"), OffsetDateTime.now()))));

        GetPerformanceInputsUseCase.Result result = service.execute(
                new GetPerformanceInputsUseCase.Query(USER, FROM, TO))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPerformanceInputsUseCase.Result.Success success =
                assertInstanceOf(GetPerformanceInputsUseCase.Result.Success.class, result);
        CapitalFlowEntry converted = success.inputs().capitalFlows().getFirst();
        assertEquals(Currency.USD, converted.currency());
        assertEquals(0, new BigDecimal("-1100.000000").compareTo(converted.amount()));
    }

    private static DailyValuation valuation(LocalDate date, boolean complete) {
        return new DailyValuation(date, Currency.USD, BigDecimal.TEN, BigDecimal.ONE, complete);
    }
}

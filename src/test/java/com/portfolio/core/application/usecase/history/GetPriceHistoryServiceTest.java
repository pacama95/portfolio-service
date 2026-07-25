package com.portfolio.core.application.usecase.history;

import com.portfolio.core.model.Currency;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPriceHistoryUseCase;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

class GetPriceHistoryServiceTest {

    private static final UserId USER = UserId.of("user-1");
    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 2);

    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private GetPriceHistoryService service;

    @BeforeEach
    void setUp() {
        service = new GetPriceHistoryService(marketDataPort, 3650);
    }

    @Test
    void givenRangeExceedsMax_whenExecute_thenInvalidRequest() {
        GetPriceHistoryService strict = new GetPriceHistoryService(marketDataPort, 5);

        GetPriceHistoryUseCase.Result result = strict.execute(
                new GetPriceHistoryUseCase.Query(USER, List.of("AAPL"), FROM, LocalDate.of(2024, 1, 20)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPriceHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNullTo_whenExecute_thenInvalidRequest() {
        GetPriceHistoryUseCase.Result result = service.execute(
                new GetPriceHistoryUseCase.Query(USER, List.of("AAPL"), FROM, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPriceHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenSameFromAndTo_whenExecute_thenReturnsEntriesForSingleDay() {
        PriceHistoryEntry aapl = entry("AAPL", FROM, "100");
        when(marketDataPort.getPriceHistory("AAPL", FROM, FROM)).thenReturn(Uni.createFrom().item(List.of(aapl)));

        GetPriceHistoryUseCase.Result result = service.execute(
                new GetPriceHistoryUseCase.Query(USER, List.of("AAPL"), FROM, FROM))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPriceHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPriceHistoryUseCase.Result.Success.class, result);
        assertEquals(1, success.entries().size());
    }

    @Test
    void givenNullSymbols_whenExecute_thenInvalidRequest() {
        GetPriceHistoryUseCase.Result result = service.execute(
                new GetPriceHistoryUseCase.Query(USER, null, FROM, TO))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPriceHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNullFrom_whenExecute_thenInvalidRequest() {
        GetPriceHistoryUseCase.Result result = service.execute(
                new GetPriceHistoryUseCase.Query(USER, List.of("AAPL"), null, TO))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPriceHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenFromAfterTo_whenExecute_thenInvalidRequest() {
        GetPriceHistoryUseCase.Result result = service.execute(
                new GetPriceHistoryUseCase.Query(
                        USER, List.of("AAPL"), LocalDate.of(2024, 2, 1), LocalDate.of(2024, 1, 1)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPriceHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenEmptySymbols_whenExecute_thenInvalidRequest() {
        GetPriceHistoryUseCase.Result result = service.execute(
                new GetPriceHistoryUseCase.Query(USER, List.of(), FROM, TO))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPriceHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenMultipleSymbols_whenExecute_thenConcatenatesEntries() {
        PriceHistoryEntry aapl = entry("AAPL", FROM, "100");
        PriceHistoryEntry msft = entry("MSFT", FROM, "200");
        when(marketDataPort.getPriceHistory("AAPL", FROM, TO)).thenReturn(Uni.createFrom().item(List.of(aapl)));
        when(marketDataPort.getPriceHistory("MSFT", FROM, TO)).thenReturn(Uni.createFrom().item(List.of(msft)));

        GetPriceHistoryUseCase.Result result = service.execute(
                new GetPriceHistoryUseCase.Query(USER, List.of("AAPL", "MSFT"), FROM, TO))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPriceHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPriceHistoryUseCase.Result.Success.class, result);
        assertEquals(2, success.entries().size());
        assertEquals("AAPL", success.entries().get(0).symbol());
        assertEquals("MSFT", success.entries().get(1).symbol());
    }

    private static PriceHistoryEntry entry(String symbol, LocalDate date, String close) {
        return new PriceHistoryEntry(
                symbol, date, new BigDecimal(close), null, Currency.USD, OffsetDateTime.now());
    }
}

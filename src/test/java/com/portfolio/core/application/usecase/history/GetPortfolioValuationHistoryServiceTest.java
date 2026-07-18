package com.portfolio.core.application.usecase.history;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyValuation;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPortfolioValuationHistoryUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class GetPortfolioValuationHistoryServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private GetPortfolioValuationHistoryService service;

    @BeforeEach
    void setUp() {
        service = new GetPortfolioValuationHistoryService(transactionRepository, marketDataPort, "USD");
    }

    @Test
    void givenNullFrom_whenExecute_thenInvalidRequest() {
        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, null, LocalDate.of(2024, 1, 31)))
                .await().indefinitely();

        assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenSameFromAndTo_whenExecute_thenReturnsSingleDayValuation() {
        LocalDate date = LocalDate.of(2024, 1, 10);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)))));
        when(marketDataPort.getPriceHistory("AAPL", date, date)).thenReturn(Uni.createFrom().item(List.of(
                price("AAPL", date, "110"))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, date, date))
                .await().indefinitely();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        assertEquals(1, success.valuations().size());
    }

    @Test
    void givenNullTo_whenExecute_thenInvalidRequest() {
        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, LocalDate.of(2024, 1, 1), null))
                .await().indefinitely();

        assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenFromAfterTo_whenExecute_thenInvalidRequest() {
        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(
                        USER, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 1, 1)))
                .await().indefinitely();

        assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNoTransactions_whenExecute_thenEmptyValuations() {
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of()));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(
                        USER, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31)))
                .await().indefinitely();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        assertTrue(success.valuations().isEmpty());
    }

    @Test
    void givenPositionAndFreshPrices_whenExecute_thenCompleteValuation() {
        LocalDate date = LocalDate.of(2024, 1, 10);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)))));
        when(marketDataPort.getPriceHistory("AAPL", date, date)).thenReturn(Uni.createFrom().item(List.of(
                price("AAPL", date, "110"))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, date, date))
                .await().indefinitely();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        assertEquals(1, success.valuations().size());
        DailyValuation valuation = success.valuations().getFirst();
        assertEquals(date, valuation.date());
        assertEquals(Currency.USD, valuation.valuationCurrency());
        assertEquals(0, new BigDecimal("1100.000000").compareTo(valuation.totalMarketValue()));
        assertEquals(0, new BigDecimal("1000").compareTo(valuation.totalCost()));
        assertTrue(valuation.complete());
    }

    @Test
    void givenStalePrice_whenExecute_thenMarksIncomplete() {
        LocalDate valuationDate = LocalDate.of(2024, 1, 10);
        LocalDate stalePriceDate = LocalDate.of(2024, 1, 1);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)))));
        when(marketDataPort.getPriceHistory("AAPL", valuationDate, valuationDate))
                .thenReturn(Uni.createFrom().item(List.of(price("AAPL", stalePriceDate, "110"))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, valuationDate, valuationDate))
                .await().indefinitely();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        DailyValuation valuation = success.valuations().getFirst();
        assertFalse(valuation.complete());
        assertEquals(0, BigDecimal.ZERO.compareTo(valuation.totalMarketValue()));
    }

    @Test
    void givenZeroShares_whenExecute_thenSkipsValuationDay() {
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "10", "110", LocalDate.of(2024, 1, 2)))));
        when(marketDataPort.getPriceHistory("AAPL", LocalDate.of(2024, 1, 3), LocalDate.of(2024, 1, 5)))
                .thenReturn(Uni.createFrom().item(List.of(
                        price("AAPL", LocalDate.of(2024, 1, 3), "120"),
                        price("AAPL", LocalDate.of(2024, 1, 4), "121"),
                        price("AAPL", LocalDate.of(2024, 1, 5), "122"))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(
                        USER, LocalDate.of(2024, 1, 3), LocalDate.of(2024, 1, 5)))
                .await().indefinitely();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        assertTrue(success.valuations().isEmpty());
    }

    private static Transaction tx(String ticker, TransactionType type, String qty, String price, LocalDate date) {
        return new Transaction(
                UUID.randomUUID(),
                USER,
                ticker,
                type,
                AssetType.COMMON_STOCK,
                new BigDecimal(qty),
                new BigDecimal(price),
                BigDecimal.ZERO,
                Currency.USD,
                date,
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                ticker + " Inc",
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));
    }

    private static PriceHistoryEntry price(String symbol, LocalDate date, String close) {
        return new PriceHistoryEntry(
                symbol, date, new BigDecimal(close), null, Currency.USD, OffsetDateTime.now());
    }
}

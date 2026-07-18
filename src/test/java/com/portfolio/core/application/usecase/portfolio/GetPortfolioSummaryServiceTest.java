package com.portfolio.core.application.usecase.portfolio;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.PortfolioSummary;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPortfolioSummaryUseCase;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

class GetPortfolioSummaryServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private GetPortfolioSummaryService service;

    @BeforeEach
    void setUp() {
        service = new GetPortfolioSummaryService(transactionRepository, marketDataPort, "USD");
    }

    @Test
    void givenNoTransactions_whenExecute_thenEmptySummary() {
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of()));

        GetPortfolioSummaryUseCase.Result result = service.execute(GetPortfolioSummaryUseCase.Query.all(USER))
                .await().indefinitely();

        GetPortfolioSummaryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioSummaryUseCase.Result.Success.class, result);
        PortfolioSummary summary = success.summary();
        assertEquals(Currency.USD, summary.valuationCurrency());
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.totalMarketValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.totalCost()));
        assertEquals(0, summary.totalPositions());
        assertEquals(0, summary.activePositions());
    }

    @Test
    void givenSameCurrencyPositions_whenExecute_thenAggregatesMarketValueAndCost() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", Currency.USD, LocalDate.of(2024, 1, 1)),
                tx("MSFT", TransactionType.BUY, "5", "200", Currency.USD, LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.getSpotPrice("AAPL")).thenReturn(Uni.createFrom().item(new BigDecimal("150")));
        when(marketDataPort.getSpotPrice("MSFT")).thenReturn(Uni.createFrom().item(new BigDecimal("250")));

        GetPortfolioSummaryUseCase.Result result = service.execute(GetPortfolioSummaryUseCase.Query.all(USER))
                .await().indefinitely();

        GetPortfolioSummaryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioSummaryUseCase.Result.Success.class, result);
        PortfolioSummary summary = success.summary();
        assertEquals(Currency.USD, summary.valuationCurrency());
        assertEquals(0, new BigDecimal("2750.000000").compareTo(summary.totalMarketValue()));
        assertEquals(0, new BigDecimal("2000").compareTo(summary.totalCost()));
        assertEquals(2, summary.totalPositions());
        assertEquals(2, summary.activePositions());
    }

    @Test
    void givenForeignCurrencyPosition_whenExecute_thenConvertsToBaseCurrency() {
        List<Transaction> txs = List.of(
                tx("SAP", TransactionType.BUY, "10", "100", Currency.EUR, LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.getSpotPrice("SAP")).thenReturn(Uni.createFrom().item(new BigDecimal("120")));
        when(marketDataPort.getFxRate(Currency.EUR, Currency.USD))
                .thenReturn(Uni.createFrom().item(new BigDecimal("1.10")));

        GetPortfolioSummaryUseCase.Result result = service.execute(GetPortfolioSummaryUseCase.Query.all(USER))
                .await().indefinitely();

        GetPortfolioSummaryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioSummaryUseCase.Result.Success.class, result);
        PortfolioSummary summary = success.summary();
        assertEquals(0, new BigDecimal("1320.000000").compareTo(summary.totalMarketValue()));
        assertEquals(0, new BigDecimal("1100.000000").compareTo(summary.totalCost()));
    }

    @Test
    void givenFxRateFailure_whenExecute_thenUsesRateOne() {
        List<Transaction> txs = List.of(
                tx("SAP", TransactionType.BUY, "10", "100", Currency.EUR, LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.getSpotPrice("SAP")).thenReturn(Uni.createFrom().item(new BigDecimal("120")));
        when(marketDataPort.getFxRate(Currency.EUR, Currency.USD))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("fx down")));

        GetPortfolioSummaryUseCase.Result result = service.execute(GetPortfolioSummaryUseCase.Query.all(USER))
                .await().indefinitely();

        GetPortfolioSummaryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioSummaryUseCase.Result.Success.class, result);
        PortfolioSummary summary = success.summary();
        assertEquals(0, new BigDecimal("1200.000000").compareTo(summary.totalMarketValue()));
        assertEquals(0, new BigDecimal("1000.000000").compareTo(summary.totalCost()));
    }

    @Test
    void givenActiveOnly_whenExecute_thenExcludesInactivePositions() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", Currency.USD, LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "10", "110", Currency.USD, LocalDate.of(2024, 1, 2)),
                tx("MSFT", TransactionType.BUY, "5", "200", Currency.USD, LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.getSpotPrice("MSFT")).thenReturn(Uni.createFrom().item(new BigDecimal("210")));

        GetPortfolioSummaryUseCase.Result result = service.execute(GetPortfolioSummaryUseCase.Query.active(USER))
                .await().indefinitely();

        GetPortfolioSummaryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioSummaryUseCase.Result.Success.class, result);
        PortfolioSummary summary = success.summary();
        assertEquals(1, summary.totalPositions());
        assertEquals(1, summary.activePositions());
        assertEquals(0, new BigDecimal("1050.000000").compareTo(summary.totalMarketValue()));
    }

    @Test
    void givenNullPositionCurrency_whenExecute_thenSkipsFxConversion() {
        Transaction tx = new Transaction(
                UUID.randomUUID(),
                USER,
                "AAPL",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("10"),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                null,
                LocalDate.of(2024, 1, 1),
                null,
                false,
                BigDecimal.ONE,
                null,
                null,
                null,
                null,
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(tx)));
        when(marketDataPort.getSpotPrice("AAPL")).thenReturn(Uni.createFrom().item(new BigDecimal("150")));

        GetPortfolioSummaryUseCase.Result result = service.execute(GetPortfolioSummaryUseCase.Query.all(USER))
                .await().indefinitely();

        GetPortfolioSummaryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioSummaryUseCase.Result.Success.class, result);
        assertEquals(0, new BigDecimal("1500.000000").compareTo(success.summary().totalMarketValue()));
        assertEquals(0, new BigDecimal("1000").compareTo(success.summary().totalCost()));
    }

    @Test
    void givenSpotPriceFailure_whenExecute_thenMarketValueIsZero() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", Currency.USD, LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.getSpotPrice("AAPL"))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("spot down")));

        GetPortfolioSummaryUseCase.Result result = service.execute(GetPortfolioSummaryUseCase.Query.all(USER))
                .await().indefinitely();

        GetPortfolioSummaryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioSummaryUseCase.Result.Success.class, result);
        PortfolioSummary summary = success.summary();
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.totalMarketValue()));
        assertEquals(0, new BigDecimal("1000").compareTo(summary.totalCost()));
        assertEquals(1, summary.totalPositions());
    }

    private static Transaction tx(
            String ticker,
            TransactionType type,
            String qty,
            String price,
            Currency currency,
            LocalDate date) {
        return new Transaction(
                UUID.randomUUID(),
                USER,
                ticker,
                type,
                AssetType.COMMON_STOCK,
                new BigDecimal(qty),
                new BigDecimal(price),
                BigDecimal.ZERO,
                currency,
                date,
                null,
                false,
                BigDecimal.ONE,
                currency,
                "EXCHANGE",
                "US",
                ticker + " Inc",
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));
    }
}

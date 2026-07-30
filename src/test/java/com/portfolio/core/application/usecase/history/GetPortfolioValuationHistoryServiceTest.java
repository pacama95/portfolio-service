package com.portfolio.core.application.usecase.history;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyValuation;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPortfolioValuationHistoryUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetPortfolioValuationHistoryServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private GetPortfolioValuationHistoryService service;

    @BeforeEach
    void setUp() {
        service = new GetPortfolioValuationHistoryService(transactionRepository, marketDataPort, "USD", 5500);
    }

    @Test
    void givenNullFrom_whenExecute_thenInvalidRequest() {
        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, null, LocalDate.of(2024, 1, 31)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenRangeExceedsMax_whenExecute_thenInvalidRequest() {
        GetPortfolioValuationHistoryService strict =
                new GetPortfolioValuationHistoryService(transactionRepository, marketDataPort, "USD", 5);

        GetPortfolioValuationHistoryUseCase.Result result = strict.execute(
                new GetPortfolioValuationHistoryUseCase.Query(
                        USER, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 20)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenSameFromAndTo_whenExecute_thenReturnsSingleDayValuation() {
        LocalDate date = LocalDate.of(2024, 1, 10);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)))));
        when(marketDataPort.getPriceHistory("AAPL", date.minusDays(5), date)).thenReturn(Uni.createFrom().item(List.of(
                price("AAPL", date, "110"))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, date, date))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        assertEquals(1, success.valuations().size());
    }

    @Test
    void givenNullTo_whenExecute_thenInvalidRequest() {
        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, LocalDate.of(2024, 1, 1), null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenFromAfterTo_whenExecute_thenInvalidRequest() {
        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(
                        USER, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 1, 1)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNoTransactions_whenExecute_thenEmptyValuations() {
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of()));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(
                        USER, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        assertTrue(success.valuations().isEmpty());
    }

    @Test
    void givenPositionAndFreshPrices_whenExecute_thenCompleteValuation() {
        LocalDate date = LocalDate.of(2024, 1, 10);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)))));
        when(marketDataPort.getPriceHistory("AAPL", date.minusDays(5), date)).thenReturn(Uni.createFrom().item(List.of(
                price("AAPL", date, "110"))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, date, date))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

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
        when(marketDataPort.getPriceHistory("AAPL", valuationDate.minusDays(5), valuationDate))
                .thenReturn(Uni.createFrom().item(List.of(price("AAPL", stalePriceDate, "110"))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, valuationDate, valuationDate))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        DailyValuation valuation = success.valuations().getFirst();
        assertFalse(valuation.complete());
        assertEquals(0, BigDecimal.ZERO.compareTo(valuation.totalMarketValue()));
        // S4: cost basis is a pure ledger number and must not be dropped just because the price
        // lookup failed — only market value should be excluded.
        assertEquals(0, new BigDecimal("1000").compareTo(valuation.totalCost()));
    }

    @Test
    void givenZeroShares_whenExecute_thenSkipsValuationDay() {
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "10", "110", LocalDate.of(2024, 1, 2)))));
        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(
                        USER, LocalDate.of(2024, 1, 3), LocalDate.of(2024, 1, 5)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        assertTrue(success.valuations().isEmpty());
        verify(marketDataPort, never()).getPriceHistory(
                Mockito.anyString(), Mockito.any(LocalDate.class), Mockito.any(LocalDate.class));
    }

    @Test
    void givenRangeStartsBeforeFirstHolding_whenExecute_thenFetchStartsAtHoldingWindow() {
        LocalDate rangeFrom = LocalDate.of(2011, 7, 20);
        LocalDate purchaseDate = LocalDate.of(2026, 7, 10);
        LocalDate rangeTo = LocalDate.of(2026, 7, 20);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", purchaseDate))));
        when(marketDataPort.getPriceHistory("AAPL", purchaseDate.minusDays(5), rangeTo))
                .thenReturn(Uni.createFrom().item(List.of(price("AAPL", purchaseDate, "110"))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, rangeFrom, rangeTo))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        assertEquals(purchaseDate, success.valuations().getFirst().date());
        verify(marketDataPort).getPriceHistory("AAPL", purchaseDate.minusDays(5), rangeTo);
    }

    @Test
    void givenForeignHoldingStartsAfterRangeStart_whenExecute_thenFxStartsAtHoldingWindow() {
        LocalDate rangeFrom = LocalDate.of(2024, 1, 1);
        LocalDate purchaseDate = LocalDate.of(2026, 7, 10);
        LocalDate rangeTo = LocalDate.of(2026, 7, 20);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("ASML", TransactionType.BUY, "10", "100", purchaseDate, Currency.EUR))));
        when(marketDataPort.getPriceHistory("ASML", purchaseDate.minusDays(5), rangeTo))
                .thenReturn(Uni.createFrom().item(List.of(
                        new PriceHistoryEntry(
                                "ASML", purchaseDate, new BigDecimal("110"), null,
                                Currency.EUR, OffsetDateTime.now()))));
        when(marketDataPort.getFxHistory(Currency.EUR, Currency.USD, purchaseDate.minusDays(7), rangeTo))
                .thenReturn(Uni.createFrom().item(List.of(
                        new FxRateEntry(
                                Currency.EUR, Currency.USD, purchaseDate,
                                new BigDecimal("1.10"), OffsetDateTime.now()))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, rangeFrom, rangeTo))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        assertEquals(purchaseDate, success.valuations().getFirst().date());
        verify(marketDataPort).getFxHistory(
                Currency.EUR, Currency.USD, purchaseDate.minusDays(7), rangeTo);
    }

    @Test
    void givenForeignCurrencyPositionWithFxRate_whenExecute_thenConvertsToBaseCurrency() {
        LocalDate purchaseDate = LocalDate.of(2024, 1, 1);
        LocalDate date = LocalDate.of(2024, 1, 10);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("ASML", TransactionType.BUY, "10", "100", purchaseDate, Currency.EUR))));
        when(marketDataPort.getPriceHistory("ASML", date.minusDays(5), date)).thenReturn(Uni.createFrom().item(List.of(
                new PriceHistoryEntry("ASML", date, new BigDecimal("110"), null, Currency.EUR, OffsetDateTime.now()))));
        when(marketDataPort.getFxHistory(Currency.EUR, Currency.USD, date.minusDays(7), date))
                .thenReturn(Uni.createFrom().item(List.of(
                        new FxRateEntry(Currency.EUR, Currency.USD, date, new BigDecimal("1.10"), OffsetDateTime.now()))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, date, date))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        DailyValuation valuation = success.valuations().getFirst();
        assertEquals(Currency.USD, valuation.valuationCurrency());
        // 10 shares * 110 EUR * 1.10 EUR/USD = 1210 USD
        assertEquals(0, new BigDecimal("1210.000000").compareTo(valuation.totalMarketValue()));
        // 10 * 100 EUR invested * 1.10 EUR/USD = 1100 USD
        assertEquals(0, new BigDecimal("1100.000000").compareTo(valuation.totalCost()));
        assertTrue(valuation.complete());
    }

    @Test
    void givenForeignCurrencyPositionStalePriceWithFxRate_whenExecute_thenCostStillIncluded() {
        LocalDate purchaseDate = LocalDate.of(2024, 1, 1);
        LocalDate date = LocalDate.of(2024, 1, 10);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("ASML", TransactionType.BUY, "10", "100", purchaseDate, Currency.EUR))));
        // No price entries at all in the fetched range -> price lookup is empty (stale/missing).
        when(marketDataPort.getPriceHistory("ASML", date.minusDays(5), date))
                .thenReturn(Uni.createFrom().item(List.of()));
        when(marketDataPort.getFxHistory(Currency.EUR, Currency.USD, date.minusDays(7), date))
                .thenReturn(Uni.createFrom().item(List.of(
                        new FxRateEntry(Currency.EUR, Currency.USD, date, new BigDecimal("1.10"), OffsetDateTime.now()))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, date, date))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        DailyValuation valuation = success.valuations().getFirst();
        assertFalse(valuation.complete());
        assertEquals(0, BigDecimal.ZERO.compareTo(valuation.totalMarketValue()));
        // S4: FX rate is available, so cost basis (which only needs FX, not price) is still
        // included even though the price is missing.
        assertEquals(0, new BigDecimal("1100.000000").compareTo(valuation.totalCost()));
    }

    @Test
    void givenForeignCurrencyPositionWithoutFxRate_whenExecute_thenMarksIncomplete() {
        LocalDate purchaseDate = LocalDate.of(2024, 1, 1);
        LocalDate date = LocalDate.of(2024, 1, 10);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("ASML", TransactionType.BUY, "10", "100", purchaseDate, Currency.EUR))));
        when(marketDataPort.getPriceHistory("ASML", date.minusDays(5), date)).thenReturn(Uni.createFrom().item(List.of(
                new PriceHistoryEntry("ASML", date, new BigDecimal("110"), null, Currency.EUR, OffsetDateTime.now()))));
        when(marketDataPort.getFxHistory(Currency.EUR, Currency.USD, date.minusDays(7), date))
                .thenReturn(Uni.createFrom().item(List.of()));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, date, date))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPortfolioValuationHistoryUseCase.Result.Success success =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result);
        DailyValuation valuation = success.valuations().getFirst();
        assertFalse(valuation.complete());
        assertEquals(0, BigDecimal.ZERO.compareTo(valuation.totalMarketValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(valuation.totalCost()));
    }

    @Test
    void givenInconsistentLedger_whenExecute_thenConflict() {
        LocalDate date = LocalDate.of(2024, 1, 10);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("AAPL", TransactionType.BUY, "5", "100", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "10", "100", LocalDate.of(2024, 1, 5)))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, LocalDate.of(2024, 1, 1), date))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPortfolioValuationHistoryUseCase.Result.Conflict conflict =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Conflict.class, result);
        assertTrue(conflict.message().contains("AAPL"));
    }

    /**
     * The whole construction in one pass: two tickers in two currencies over ten days, with a
     * weekend gap bridged by the price floor, a foreign price going stale mid-range (days marked
     * incomplete while cost keeps accumulating), and the foreign position exiting before the end.
     */
    @Test
    void givenMultiCurrencyPortfolioWithGapsAndExit_whenExecute_thenBuildsExpectedDailySeries() {
        LocalDate from = LocalDate.of(2024, 1, 8);
        LocalDate to = LocalDate.of(2024, 1, 17);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("ACME", TransactionType.BUY, "20", "100", from),
                tx("BRIT", TransactionType.BUY, "10", "2.5", from, Currency.GBP),
                tx("BRIT", TransactionType.SELL, "10", "3", LocalDate.of(2024, 1, 17), Currency.GBP))));
        when(marketDataPort.getPriceHistory("ACME", from.minusDays(5), to))
                .thenReturn(Uni.createFrom().item(List.of(
                        price("ACME", LocalDate.of(2024, 1, 8), "110"),
                        price("ACME", LocalDate.of(2024, 1, 9), "112"),
                        price("ACME", LocalDate.of(2024, 1, 10), "114"),
                        price("ACME", LocalDate.of(2024, 1, 11), "116"),
                        price("ACME", LocalDate.of(2024, 1, 12), "120"),
                        price("ACME", LocalDate.of(2024, 1, 15), "122"),
                        price("ACME", LocalDate.of(2024, 1, 16), "124"),
                        price("ACME", LocalDate.of(2024, 1, 17), "126"))));
        // BRIT quotes stop on the 9th: the floor carries through the 14th (five-day staleness),
        // then the 15th and 16th have no usable price.
        when(marketDataPort.getPriceHistory("BRIT", from.minusDays(5), to))
                .thenReturn(Uni.createFrom().item(List.of(
                        price("BRIT", LocalDate.of(2024, 1, 8), "2.5"),
                        price("BRIT", LocalDate.of(2024, 1, 9), "2.6"))));
        when(marketDataPort.getFxHistory(Currency.GBP, Currency.USD, from.minusDays(7), to))
                .thenReturn(Uni.createFrom().item(List.of(
                        fx(LocalDate.of(2024, 1, 8), "1.25"),
                        fx(LocalDate.of(2024, 1, 9), "1.25"),
                        fx(LocalDate.of(2024, 1, 10), "1.25"),
                        fx(LocalDate.of(2024, 1, 11), "1.25"),
                        fx(LocalDate.of(2024, 1, 12), "1.25"),
                        fx(LocalDate.of(2024, 1, 15), "1.25"),
                        fx(LocalDate.of(2024, 1, 16), "1.25"),
                        fx(LocalDate.of(2024, 1, 17), "1.25"))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, from, to))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        List<DailyValuation> valuations =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result)
                        .valuations();
        assertEquals(10, valuations.size());
        assertDay(valuations.get(0), LocalDate.of(2024, 1, 8), "2231.25", true);
        assertDay(valuations.get(1), LocalDate.of(2024, 1, 9), "2272.5", true);
        assertDay(valuations.get(2), LocalDate.of(2024, 1, 10), "2312.5", true);
        assertDay(valuations.get(3), LocalDate.of(2024, 1, 11), "2352.5", true);
        assertDay(valuations.get(4), LocalDate.of(2024, 1, 12), "2432.5", true);
        // Weekend: both series floor to Friday's values.
        assertDay(valuations.get(5), LocalDate.of(2024, 1, 13), "2432.5", true);
        assertDay(valuations.get(6), LocalDate.of(2024, 1, 14), "2432.5", true);
        // BRIT price stale: its market value drops out and the day is incomplete...
        assertDay(valuations.get(7), LocalDate.of(2024, 1, 15), "2440", false);
        assertDay(valuations.get(8), LocalDate.of(2024, 1, 16), "2480", false);
        // ...but its cost basis does not depend on the price and stays in.
        assertEquals(0, new BigDecimal("2031.25").compareTo(valuations.get(7).totalCost()));
        // Exit day: BRIT's zero-share snapshot no longer participates, so the day is complete.
        assertDay(valuations.get(9), LocalDate.of(2024, 1, 17), "2520", true);
    }

    @Test
    void givenOnlyPositionSoldToZeroMidRange_whenExecute_thenDaysAfterExitAreOmitted() {
        LocalDate from = LocalDate.of(2024, 1, 8);
        LocalDate to = LocalDate.of(2024, 1, 12);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("ACME", TransactionType.BUY, "10", "100", from),
                tx("ACME", TransactionType.SELL, "10", "110", LocalDate.of(2024, 1, 10)))));
        when(marketDataPort.getPriceHistory("ACME", from.minusDays(5), to))
                .thenReturn(Uni.createFrom().item(List.of(
                        price("ACME", LocalDate.of(2024, 1, 8), "100"),
                        price("ACME", LocalDate.of(2024, 1, 9), "101"),
                        price("ACME", LocalDate.of(2024, 1, 10), "102"))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, from, to))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        List<DailyValuation> valuations =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result)
                        .valuations();
        // The exit day's end-of-day snapshot already holds zero shares, so the series ends the
        // day before, and no empty rows pad the rest of the requested range.
        assertEquals(2, valuations.size());
        assertDay(valuations.get(0), LocalDate.of(2024, 1, 8), "1000", true);
        assertDay(valuations.get(1), LocalDate.of(2024, 1, 9), "1010", true);
    }

    @Test
    void givenSplitMidRange_whenExecute_thenPostSplitSharesDriveMarketValue() {
        LocalDate from = LocalDate.of(2024, 1, 8);
        LocalDate to = LocalDate.of(2024, 1, 11);
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of(
                tx("ACME", TransactionType.BUY, "20", "100", from),
                tx("ACME", TransactionType.SPLIT, "4", "0", LocalDate.of(2024, 1, 10)))));
        when(marketDataPort.getPriceHistory("ACME", from.minusDays(5), to))
                .thenReturn(Uni.createFrom().item(List.of(
                        price("ACME", LocalDate.of(2024, 1, 8), "120"),
                        price("ACME", LocalDate.of(2024, 1, 9), "120"),
                        price("ACME", LocalDate.of(2024, 1, 10), "30"),
                        price("ACME", LocalDate.of(2024, 1, 11), "31"))));

        GetPortfolioValuationHistoryUseCase.Result result = service.execute(
                new GetPortfolioValuationHistoryUseCase.Query(USER, from, to))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        List<DailyValuation> valuations =
                assertInstanceOf(GetPortfolioValuationHistoryUseCase.Result.Success.class, result)
                        .valuations();
        // 20 x 120 before the split, 80 x 30 on split day: market value is continuous.
        assertEquals(4, valuations.size());
        assertDay(valuations.get(0), LocalDate.of(2024, 1, 8), "2400", true);
        assertDay(valuations.get(1), LocalDate.of(2024, 1, 9), "2400", true);
        assertDay(valuations.get(2), LocalDate.of(2024, 1, 10), "2400", true);
        assertDay(valuations.get(3), LocalDate.of(2024, 1, 11), "2480", true);
    }

    private static void assertDay(DailyValuation valuation, LocalDate date, String marketValue, boolean complete) {
        assertEquals(date, valuation.date());
        assertEquals(0, new BigDecimal(marketValue).compareTo(valuation.totalMarketValue()),
                () -> date + ": expected " + marketValue + " but was " + valuation.totalMarketValue());
        assertEquals(complete, valuation.complete(), () -> date + ": complete flag");
    }

    private static FxRateEntry fx(LocalDate date, String rate) {
        return new FxRateEntry(Currency.GBP, Currency.USD, date, new BigDecimal(rate), OffsetDateTime.now());
    }

    private static Transaction tx(String ticker, TransactionType type, String qty, String price, LocalDate date) {
        return tx(ticker, type, qty, price, date, Currency.USD);
    }

    private static Transaction tx(
            String ticker, TransactionType type, String qty, String price, LocalDate date, Currency currency) {
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

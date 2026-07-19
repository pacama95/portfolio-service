package com.portfolio.core.domain;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.CapitalFlowKind;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyPositionSnapshot;
import com.portfolio.core.model.Position;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerReplayTest {

    private static final UserId USER = UserId.of("user-1");

    @Test
    void givenBuyThenSell_whenReplay_thenAverageCostAndSharesCorrect() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", "1", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.BUY, "10", "120", "1", LocalDate.of(2024, 1, 2)),
                tx("AAPL", TransactionType.SELL, "5", "130", "0", LocalDate.of(2024, 1, 3))
        );

        LedgerReplay.ApplyResult result = LedgerReplay.replayTicker(txs);

        LedgerReplay.ApplyResult.Success success = assertInstanceOf(LedgerReplay.ApplyResult.Success.class, result);
        assertEquals(0, new BigDecimal("15").compareTo(success.state().sharesOwned()));
        // invested: 10*100+1 + 10*120+1 = 2202; sell 5 * avg(2202/20=110.1) = 550.5 → 1651.5; avg=110.1
        assertEquals(0, new BigDecimal("110.100000").compareTo(success.state().averageCostPerShare()));
        assertEquals(0, new BigDecimal("1651.500000").compareTo(success.state().totalInvestedAmount()));
        assertTrue(success.state().hasShares());
    }

    @Test
    void givenOversell_whenApply_thenReturnsOversellResult() {
        LedgerReplay.State state = LedgerReplay.State.empty();
        state = ((LedgerReplay.ApplyResult.Success) LedgerReplay.apply(
                state, tx("AAPL", TransactionType.BUY, "5", "100", "0", LocalDate.of(2024, 1, 1)))).state();

        LedgerReplay.ApplyResult result = LedgerReplay.apply(
                state, tx("AAPL", TransactionType.SELL, "10", "100", "0", LocalDate.of(2024, 1, 2)));

        LedgerReplay.ApplyResult.Oversell oversell = assertInstanceOf(LedgerReplay.ApplyResult.Oversell.class, result);
        assertEquals("AAPL", oversell.ticker());
        assertEquals(0, new BigDecimal("10").compareTo(oversell.requested()));
        assertEquals(0, new BigDecimal("5").compareTo(oversell.available()));
    }

    @Test
    void givenSplit_whenReplay_thenSharesScaleAndAvgDivides() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", "0", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SPLIT, "2", "0", "0", LocalDate.of(2024, 1, 2))
        );

        LedgerReplay.ApplyResult.Success success =
                assertInstanceOf(LedgerReplay.ApplyResult.Success.class, LedgerReplay.replayTicker(txs));

        assertEquals(0, new BigDecimal("20.000000").compareTo(success.state().sharesOwned()));
        assertEquals(0, new BigDecimal("50.000000").compareTo(success.state().averageCostPerShare()));
        assertEquals(0, new BigDecimal("1000").compareTo(success.state().totalInvestedAmount()));
    }

    @Test
    void givenDividend_whenReplay_thenPositionSharesUnchanged() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", "0", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.DIVIDEND, "10", "0.5", "0", LocalDate.of(2024, 1, 2))
        );

        LedgerReplay.ApplyResult.Success success =
                assertInstanceOf(LedgerReplay.ApplyResult.Success.class, LedgerReplay.replayTicker(txs));

        assertEquals(0, new BigDecimal("10").compareTo(success.state().sharesOwned()));
    }

    @Test
    void givenFullSell_whenReplay_thenPositionInactive() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", "0", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "10", "110", "0", LocalDate.of(2024, 1, 2))
        );

        Position position = LedgerReplay.replayAll(txs).getFirst();

        assertFalse(position.active());
        assertEquals(0, BigDecimal.ZERO.compareTo(position.sharesOwned()));
        assertEquals(0, BigDecimal.ZERO.compareTo(position.totalInvestedAmount()));
    }

    @Test
    void givenMultipleTickers_whenReplayAll_thenOnePositionPerTicker() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "1", "100", "0", LocalDate.of(2024, 1, 1)),
                tx("MSFT", TransactionType.BUY, "2", "200", "0", LocalDate.of(2024, 1, 1))
        );

        List<Position> positions = LedgerReplay.replayAll(txs);

        assertEquals(2, positions.size());
        assertTrue(positions.stream().anyMatch(p -> p.ticker().equals("AAPL") && p.active()));
        assertTrue(positions.stream().anyMatch(p -> p.ticker().equals("MSFT") && p.active()));
    }

    @Test
    void givenTrades_whenDailySnapshots_thenSparseRowsOnTradeDays() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", "0", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.DIVIDEND, "10", "1", "0", LocalDate.of(2024, 1, 2)),
                tx("AAPL", TransactionType.SELL, "5", "110", "0", LocalDate.of(2024, 1, 5))
        );

        List<DailyPositionSnapshot> snapshots = LedgerReplay.dailySnapshots(
                txs, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));

        assertEquals(2, snapshots.size());
        assertEquals(LocalDate.of(2024, 1, 1), snapshots.get(0).snapshotDate());
        assertEquals(LocalDate.of(2024, 1, 5), snapshots.get(1).snapshotDate());
        assertEquals(0, new BigDecimal("5").compareTo(snapshots.get(1).sharesOwned()));
    }

    @Test
    void givenTrades_whenCapitalFlows_thenBuyNegativeSellPositiveDividendPositive() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", "2", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "5", "120", "1", LocalDate.of(2024, 1, 2)),
                tx("AAPL", TransactionType.DIVIDEND, "5", "1", "0", LocalDate.of(2024, 1, 3)),
                tx("AAPL", TransactionType.SPLIT, "2", "0", "0", LocalDate.of(2024, 1, 4))
        );

        var flows = LedgerReplay.capitalFlows(txs);

        assertEquals(3, flows.size());
        assertEquals(CapitalFlowKind.BUY, flows.get(0).kind());
        assertEquals(0, new BigDecimal("-1002").compareTo(flows.get(0).amount()));
        assertEquals(CapitalFlowKind.SELL, flows.get(1).kind());
        assertEquals(0, new BigDecimal("599").compareTo(flows.get(1).amount()));
        assertEquals(CapitalFlowKind.DIVIDEND, flows.get(2).kind());
        assertEquals(0, new BigDecimal("5").compareTo(flows.get(2).amount()));
    }

    @Test
    void givenNegativeQuantity_whenApply_thenInvalidInput() {
        LedgerReplay.ApplyResult result = LedgerReplay.apply(
                LedgerReplay.State.empty(),
                tx("AAPL", TransactionType.BUY, "-1", "100", "0", LocalDate.of(2024, 1, 1)));

        assertInstanceOf(LedgerReplay.ApplyResult.InvalidInput.class, result);
    }

    @Test
    void givenEmptyTransactions_whenReplayAll_thenReturnsEmptyList() {
        assertTrue(LedgerReplay.replayAll(List.of()).isEmpty());
    }

    @Test
    void givenOversell_whenReplayAll_thenThrowsLedgerInconsistencyException() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "5", "100", "0", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "10", "100", "0", LocalDate.of(2024, 1, 2)));

        LedgerReplay.LedgerInconsistencyException ex = assertThrows(
                LedgerReplay.LedgerInconsistencyException.class, () -> LedgerReplay.replayAll(txs));

        assertTrue(ex.getMessage().contains("Oversell"));
        assertTrue(ex.getMessage().contains("AAPL"));
    }

    @Test
    void givenInvalidInput_whenReplayAll_thenThrowsLedgerInconsistencyException() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "-1", "100", "0", LocalDate.of(2024, 1, 1)));

        assertThrows(LedgerReplay.LedgerInconsistencyException.class, () -> LedgerReplay.replayAll(txs));
    }

    @Test
    void givenNullTransaction_whenApply_thenThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> LedgerReplay.apply(LedgerReplay.State.empty(), null));
    }

    @Test
    void givenEmptyTransactions_whenCapitalFlows_thenReturnsEmptyList() {
        assertTrue(LedgerReplay.capitalFlows(List.of()).isEmpty());
    }

    @Test
    void givenFlowsOutsideRange_whenCapitalFlowsWithDates_thenFiltersByDateRange() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", "0", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "5", "120", "0", LocalDate.of(2024, 1, 15)),
                tx("AAPL", TransactionType.DIVIDEND, "5", "1", "0", LocalDate.of(2024, 2, 1)));

        var flows = LedgerReplay.capitalFlows(
                txs, LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 20));

        assertEquals(1, flows.size());
        assertEquals(CapitalFlowKind.SELL, flows.getFirst().kind());
        assertEquals(LocalDate.of(2024, 1, 15), flows.getFirst().flowDate());
    }

    @Test
    void givenEmptyTransactions_whenDailySnapshots_thenReturnsEmptyList() {
        assertTrue(LedgerReplay.dailySnapshots(
                List.of(), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31)).isEmpty());
    }

    @Test
    void givenTradesBeforeFrom_whenDailySnapshots_thenSkipsEarlySnapshots() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", "0", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "5", "110", "0", LocalDate.of(2024, 1, 15)));

        List<DailyPositionSnapshot> snapshots = LedgerReplay.dailySnapshots(
                txs, LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 31));

        assertEquals(1, snapshots.size());
        assertEquals(LocalDate.of(2024, 1, 15), snapshots.getFirst().snapshotDate());
    }

    @Test
    void givenTradesAfterTo_whenDailySnapshots_thenStopsAtToDate() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", "0", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.BUY, "5", "100", "0", LocalDate.of(2024, 1, 20)));

        List<DailyPositionSnapshot> snapshots = LedgerReplay.dailySnapshots(
                txs, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 15));

        assertEquals(1, snapshots.size());
        assertEquals(0, new BigDecimal("10").compareTo(snapshots.getFirst().sharesOwned()));
    }

    @Test
    void givenOversellMidLedger_whenDailySnapshots_thenThrowsLedgerInconsistencyException() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "5", "100", "0", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "10", "100", "0", LocalDate.of(2024, 1, 5)),
                tx("AAPL", TransactionType.BUY, "3", "100", "0", LocalDate.of(2024, 1, 10)));

        assertThrows(LedgerReplay.LedgerInconsistencyException.class, () -> LedgerReplay.dailySnapshots(
                txs, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31)));
    }

    @Test
    void givenNegativeBuyPrice_whenApply_thenInvalidInput() {
        LedgerReplay.ApplyResult result = LedgerReplay.apply(
                LedgerReplay.State.empty(),
                tx("AAPL", TransactionType.BUY, "1", "-10", "0", LocalDate.of(2024, 1, 1)));

        assertInstanceOf(LedgerReplay.ApplyResult.InvalidInput.class, result);
    }

    @Test
    void givenInvalidSellQuantity_whenApply_thenInvalidInput() {
        LedgerReplay.State state = ((LedgerReplay.ApplyResult.Success) LedgerReplay.apply(
                LedgerReplay.State.empty(),
                tx("AAPL", TransactionType.BUY, "5", "100", "0", LocalDate.of(2024, 1, 1)))).state();

        LedgerReplay.ApplyResult result = LedgerReplay.apply(
                state, tx("AAPL", TransactionType.SELL, "0", "100", "0", LocalDate.of(2024, 1, 2)));

        assertInstanceOf(LedgerReplay.ApplyResult.InvalidInput.class, result);
    }

    @Test
    void givenInvalidSellPrice_whenApply_thenInvalidInput() {
        LedgerReplay.State state = ((LedgerReplay.ApplyResult.Success) LedgerReplay.apply(
                LedgerReplay.State.empty(),
                tx("AAPL", TransactionType.BUY, "5", "100", "0", LocalDate.of(2024, 1, 1)))).state();

        LedgerReplay.ApplyResult result = LedgerReplay.apply(
                state, tx("AAPL", TransactionType.SELL, "1", "-1", "0", LocalDate.of(2024, 1, 2)));

        assertInstanceOf(LedgerReplay.ApplyResult.InvalidInput.class, result);
    }

    @Test
    void givenInvalidSplitRatio_whenApply_thenInvalidInput() {
        LedgerReplay.ApplyResult result = LedgerReplay.apply(
                LedgerReplay.State.empty(),
                tx("AAPL", TransactionType.SPLIT, "0", "0", "0", LocalDate.of(2024, 1, 1)));

        assertInstanceOf(LedgerReplay.ApplyResult.InvalidInput.class, result);
    }

    @Test
    void givenSplitWithoutShares_whenApply_thenSuccessWithoutChangingShares() {
        LedgerReplay.ApplyResult.Success success = assertInstanceOf(
                LedgerReplay.ApplyResult.Success.class,
                LedgerReplay.apply(
                        LedgerReplay.State.empty(),
                        tx("AAPL", TransactionType.SPLIT, "2", "0", "0", LocalDate.of(2024, 1, 1))));

        assertEquals(0, BigDecimal.ZERO.compareTo(success.state().sharesOwned()));
    }

    @Test
    void givenNullSharesOwned_whenHasShares_thenFalse() {
        LedgerReplay.State state = new LedgerReplay.State(
                null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, null);

        assertFalse(state.hasShares());
        assertFalse(state.toPosition("AAPL", null).active());
    }

    @Test
    void givenSellWithNullFees_whenApply_thenAccumulatesZeroFees() {
        LedgerReplay.State bought = ((LedgerReplay.ApplyResult.Success) LedgerReplay.apply(
                LedgerReplay.State.empty(),
                tx("AAPL", TransactionType.BUY, "10", "100", "0", LocalDate.of(2024, 1, 1)))).state();

        LedgerReplay.ApplyResult.Success sold = assertInstanceOf(
                LedgerReplay.ApplyResult.Success.class,
                LedgerReplay.apply(bought, txWithFees(
                        "AAPL", TransactionType.SELL, "5", "110", null, LocalDate.of(2024, 1, 2))));

        assertEquals(0, BigDecimal.ZERO.compareTo(sold.state().totalTransactionFees()));
    }

    @Test
    void givenExistingMetadata_whenLaterTxHasBlankMetadata_thenKeepsExistingValues() {
        Transaction buy = tx("AAPL", TransactionType.BUY, "10", "100", "0", LocalDate.of(2024, 1, 1));
        Transaction dividend = new Transaction(
                UUID.randomUUID(),
                USER,
                "AAPL",
                TransactionType.DIVIDEND,
                AssetType.COMMON_STOCK,
                new BigDecimal("10"),
                new BigDecimal("0.5"),
                BigDecimal.ZERO,
                Currency.USD,
                LocalDate.of(2024, 1, 2),
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "  ",
                "  ",
                "  ",
                OffsetDateTime.parse("2024-01-02T00:00:00Z"),
                OffsetDateTime.parse("2024-01-02T00:00:00Z"));

        LedgerReplay.ApplyResult.Success success = assertInstanceOf(
                LedgerReplay.ApplyResult.Success.class,
                LedgerReplay.replayTicker(List.of(buy, dividend)));

        assertEquals("AAPL Inc", success.state().companyName());
        assertEquals("NASDAQ", success.state().exchange());
        assertEquals("US", success.state().country());
    }

    @Test
    void givenBuyWithNullCurrency_whenApply_thenUsesTransactionCurrencyFromEnrichment() {
        Transaction buy = new Transaction(
                UUID.randomUUID(),
                USER,
                "AAPL",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("1"),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                Currency.EUR,
                LocalDate.of(2024, 1, 1),
                null,
                false,
                BigDecimal.ONE,
                Currency.EUR,
                "XETRA",
                "DE",
                "Apple Inc",
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));

        LedgerReplay.ApplyResult.Success success = assertInstanceOf(
                LedgerReplay.ApplyResult.Success.class,
                LedgerReplay.apply(LedgerReplay.State.empty(), buy));

        assertEquals(Currency.EUR, success.state().currency());
    }

    @Test
    void givenTransactionsWithNullCreatedAtAndId_whenReplayTicker_thenOrdersDeterministically() {
        Transaction first = new Transaction(
                null,
                USER,
                "AAPL",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("1"),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                Currency.USD,
                LocalDate.of(2024, 1, 1),
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "AAPL Inc",
                null,
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        Transaction second = new Transaction(
                UUID.randomUUID(),
                USER,
                "AAPL",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("2"),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                Currency.USD,
                LocalDate.of(2024, 1, 1),
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "AAPL Inc",
                OffsetDateTime.parse("2024-01-01T01:00:00Z"),
                OffsetDateTime.parse("2024-01-01T01:00:00Z"));

        LedgerReplay.ApplyResult.Success success = assertInstanceOf(
                LedgerReplay.ApplyResult.Success.class,
                LedgerReplay.replayTicker(List.of(second, first)));

        assertEquals(0, new BigDecimal("3").compareTo(success.state().sharesOwned()));
    }

    private static Transaction tx(
            String ticker, TransactionType type, String qty, String price, String fees, LocalDate date) {
        return txWithFees(ticker, type, qty, price, new BigDecimal(fees), date);
    }

    private static Transaction txWithFees(
            String ticker,
            TransactionType type,
            String qty,
            String price,
            BigDecimal fees,
            LocalDate date) {
        return new Transaction(
                UUID.randomUUID(),
                USER,
                ticker,
                type,
                AssetType.COMMON_STOCK,
                new BigDecimal(qty),
                new BigDecimal(price),
                fees,
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
}

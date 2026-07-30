package com.portfolio.core.domain;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.CapitalFlowEntry;
import com.portfolio.core.model.CapitalFlowKind;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyPositionSnapshot;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * One multi-year ledger folded end to end, checkpointing every accumulator after each phase.
 * The per-mechanic tests in {@link LedgerReplayTest} prove each rule in isolation; this proves
 * they compose over a realistic sequence: accumulate, split, distribute, wind down to zero,
 * and re-enter.
 *
 * <p>The ledger: buy 10@100(+5) and 10@150(+5) in 2022; 4:1 split in Jan 2023; dividend
 * (80 x 0.25) in Mar 2023; sell 30@40(+10) in Jul 2023; sell the remaining 50@45(+10) in
 * Feb 2024 (position closed, invested forced to zero); re-enter with 25@50(+5) in Sep 2024.
 */
class LedgerReplayLongScenarioTest {

    private static final List<Transaction> LEDGER = List.of(
            tx(LocalDate.of(2022, 1, 10), TransactionType.BUY, "10", "100", "5"),
            tx(LocalDate.of(2022, 6, 10), TransactionType.BUY, "10", "150", "5"),
            tx(LocalDate.of(2023, 1, 16), TransactionType.SPLIT, "4", "0", "0"),
            tx(LocalDate.of(2023, 3, 1), TransactionType.DIVIDEND, "80", "0.25", "0"),
            tx(LocalDate.of(2023, 7, 3), TransactionType.SELL, "30", "40", "10"),
            tx(LocalDate.of(2024, 2, 1), TransactionType.SELL, "50", "45", "10"),
            tx(LocalDate.of(2024, 9, 2), TransactionType.BUY, "25", "50", "5"));

    @Test
    void givenMultiYearLedger_whenReplayTicker_thenEveryAccumulatorSurvivesTheFullSequence() {
        LedgerReplay.ApplyResult result = LedgerReplay.replayTicker(LEDGER);

        LedgerReplay.State state =
                assertInstanceOf(LedgerReplay.ApplyResult.Success.class, result).state();
        assertDecimal("25", state.sharesOwned());
        // Re-entry basis only: 25 x 50 + 5 fee.
        assertDecimal("1255", state.totalInvestedAmount());
        assertDecimal("50.2", state.averageCostPerShare());
        // Fees survive the zero-crossing: 5 + 5 + 10 + 10 + 5.
        assertDecimal("35", state.totalTransactionFees());
        // First purchase is a lifetime fact; closing the position does not reset it.
        assertEquals(LocalDate.of(2022, 1, 10), state.firstPurchaseDate());
    }

    @Test
    void givenMultiYearLedger_whenDailySnapshots_thenEachTradeDayCarriesExactState() {
        List<DailyPositionSnapshot> snapshots = LedgerReplay.dailySnapshots(
                LEDGER, LocalDate.of(2022, 1, 1), LocalDate.of(2024, 12, 31));

        // Six trade days; the dividend changes no position and produces no row.
        assertEquals(6, snapshots.size());
        assertSnapshot(snapshots.get(0), LocalDate.of(2022, 1, 10), "10", "100.5", "1005");
        assertSnapshot(snapshots.get(1), LocalDate.of(2022, 6, 10), "20", "125.5", "2510");
        // Split: shares x4, average /4, invested untouched.
        assertSnapshot(snapshots.get(2), LocalDate.of(2023, 1, 16), "80", "31.375", "2510");
        // Partial sell removes proportional cost: 2510 - 30 x 31.375.
        assertSnapshot(snapshots.get(3), LocalDate.of(2023, 7, 3), "50", "31.375", "1568.75");
        assertSnapshot(snapshots.get(4), LocalDate.of(2024, 2, 1), "0", "0", "0");
        assertSnapshot(snapshots.get(5), LocalDate.of(2024, 9, 2), "25", "50.2", "1255");
    }

    @Test
    void givenMidLedgerWindow_whenDailySnapshots_thenStateCarriesInFromBeforeTheWindow() {
        List<DailyPositionSnapshot> snapshots = LedgerReplay.dailySnapshots(
                LEDGER, LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31));

        // Only 2023's trade days are emitted, but their state includes the 2022 buys.
        assertEquals(2, snapshots.size());
        assertSnapshot(snapshots.get(0), LocalDate.of(2023, 1, 16), "80", "31.375", "2510");
        assertSnapshot(snapshots.get(1), LocalDate.of(2023, 7, 3), "50", "31.375", "1568.75");
    }

    @Test
    void givenMultiYearLedger_whenCapitalFlows_thenSignsAndAmountsMatchEveryPhase() {
        List<CapitalFlowEntry> flows = LedgerReplay.capitalFlows(LEDGER);

        // Six flows in chronological order; the split moves no capital and is absent.
        assertEquals(6, flows.size());
        assertFlow(flows.get(0), CapitalFlowKind.BUY, "-1005", "5");
        assertFlow(flows.get(1), CapitalFlowKind.BUY, "-1505", "5");
        assertFlow(flows.get(2), CapitalFlowKind.DIVIDEND, "20", "0");
        assertFlow(flows.get(3), CapitalFlowKind.SELL, "1190", "10");
        assertFlow(flows.get(4), CapitalFlowKind.SELL, "2240", "10");
        assertFlow(flows.get(5), CapitalFlowKind.BUY, "-1255", "5");
    }

    private static void assertSnapshot(
            DailyPositionSnapshot snapshot, LocalDate date, String shares, String avgCost, String invested) {
        assertEquals(date, snapshot.snapshotDate());
        assertDecimal(shares, snapshot.sharesOwned());
        assertDecimal(avgCost, snapshot.averageCostPerShare());
        assertDecimal(invested, snapshot.totalInvestedAmount());
    }

    private static void assertFlow(CapitalFlowEntry flow, CapitalFlowKind kind, String amount, String fees) {
        assertEquals(kind, flow.kind());
        assertDecimal(amount, flow.amount());
        assertDecimal(fees, flow.fees());
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private static Transaction tx(
            LocalDate date, TransactionType type, String qty, String price, String fees) {
        return new Transaction(
                UUID.randomUUID(),
                com.portfolio.core.model.UserId.of("user-1"),
                "ACME",
                type,
                AssetType.COMMON_STOCK,
                new BigDecimal(qty),
                new BigDecimal(price),
                new BigDecimal(fees),
                Currency.USD,
                date,
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "Acme Corp",
                OffsetDateTime.parse("2022-01-01T00:00:00Z"),
                OffsetDateTime.parse("2022-01-01T00:00:00Z"));
    }
}

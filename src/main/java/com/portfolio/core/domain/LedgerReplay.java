package com.portfolio.core.domain;

import com.portfolio.core.model.CapitalFlowEntry;
import com.portfolio.core.model.CapitalFlowKind;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyPositionSnapshot;
import com.portfolio.core.model.Position;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Pure ledger fold: turns chronologically ordered transactions into positions,
 * daily snapshots, and capital flows.
 */
public final class LedgerReplay {

    private LedgerReplay() {
    }

    public sealed interface ApplyResult {
        record Success(State state) implements ApplyResult {}
        record Oversell(String ticker, BigDecimal requested, BigDecimal available) implements ApplyResult {}
        record InvalidInput(String message) implements ApplyResult {}
    }

    public record State(
            BigDecimal sharesOwned,
            BigDecimal averageCostPerShare,
            BigDecimal totalInvestedAmount,
            BigDecimal totalTransactionFees,
            Currency currency,
            LocalDate firstPurchaseDate,
            String companyName,
            String exchange,
            String country
    ) {
        public static State empty() {
            return new State(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null, null, null, null, null);
        }

        public boolean hasShares() {
            return sharesOwned != null && sharesOwned.compareTo(BigDecimal.ZERO) > 0;
        }

        public Position toPosition(String ticker, BigDecimal latestMarketPrice) {
            return new Position(
                    ticker,
                    companyName,
                    sharesOwned,
                    averageCostPerShare,
                    totalInvestedAmount,
                    totalTransactionFees,
                    currency,
                    latestMarketPrice,
                    firstPurchaseDate,
                    hasShares(),
                    exchange,
                    country);
        }
    }

    public static ApplyResult apply(State state, Transaction tx) {
        Objects.requireNonNull(tx, "transaction");
        return switch (tx.transactionType()) {
            case BUY -> applyBuy(state, tx);
            case SELL -> applySell(state, tx);
            case SPLIT -> applySplit(state, tx);
            case DIVIDEND -> new ApplyResult.Success(enrichMetadata(state, tx));
        };
    }

    public static List<Position> replayAll(List<Transaction> transactions) {
        Map<String, List<Transaction>> byTicker = groupByTicker(transactions);
        List<Position> positions = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : byTicker.entrySet()) {
            ApplyResult result = replayTicker(entry.getValue());
            if (result instanceof ApplyResult.Success success) {
                positions.add(success.state().toPosition(entry.getKey(), null));
            } else if (result instanceof ApplyResult.Oversell oversell) {
                throw new IllegalStateException(
                        "Oversell for " + oversell.ticker() + ": requested " + oversell.requested()
                                + " available " + oversell.available());
            } else if (result instanceof ApplyResult.InvalidInput invalid) {
                throw new IllegalArgumentException(invalid.message());
            }
        }
        return positions;
    }

    public static ApplyResult replayTicker(List<Transaction> transactions) {
        State state = State.empty();
        List<Transaction> ordered = chronological(transactions);
        for (Transaction tx : ordered) {
            ApplyResult result = apply(state, tx);
            if (result instanceof ApplyResult.Success success) {
                state = success.state();
            } else {
                return result;
            }
        }
        return new ApplyResult.Success(state);
    }

    /**
     * Sparse snapshots: one row per ticker per day that had a position-changing trade.
     */
    public static List<DailyPositionSnapshot> dailySnapshots(
            List<Transaction> transactions, LocalDate from, LocalDate to) {
        Map<String, List<Transaction>> byTicker = groupByTicker(transactions);
        List<DailyPositionSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : byTicker.entrySet()) {
            String ticker = entry.getKey();
            State state = State.empty();
            for (Transaction tx : chronological(entry.getValue())) {
                if (tx.transactionDate().isAfter(to)) {
                    break;
                }
                ApplyResult result = apply(state, tx);
                if (!(result instanceof ApplyResult.Success success)) {
                    continue;
                }
                state = success.state();
                if (tx.transactionDate().isBefore(from)) {
                    continue;
                }
                if (tx.transactionType() == TransactionType.DIVIDEND) {
                    continue;
                }
                snapshots.add(new DailyPositionSnapshot(
                        ticker,
                        tx.transactionDate(),
                        state.sharesOwned(),
                        state.averageCostPerShare(),
                        state.totalInvestedAmount(),
                        state.currency()));
            }
        }
        snapshots.sort(Comparator
                .comparing(DailyPositionSnapshot::snapshotDate)
                .thenComparing(DailyPositionSnapshot::ticker));
        return snapshots;
    }

    public static List<CapitalFlowEntry> capitalFlows(List<Transaction> transactions) {
        return chronological(transactions).stream()
                .filter(tx -> tx.transactionType() != TransactionType.SPLIT)
                .map(LedgerReplay::toCapitalFlow)
                .flatMap(List::stream)
                .toList();
    }

    public static List<CapitalFlowEntry> capitalFlows(
            List<Transaction> transactions, LocalDate from, LocalDate to) {
        return capitalFlows(transactions).stream()
                .filter(flow -> !flow.flowDate().isBefore(from) && !flow.flowDate().isAfter(to))
                .toList();
    }

    private static List<CapitalFlowEntry> toCapitalFlow(Transaction tx) {
        BigDecimal fees = valueOrZero(tx.fees());
        return switch (tx.transactionType()) {
            case BUY -> List.of(new CapitalFlowEntry(
                    tx.id(), tx.ticker(), tx.transactionDate(), CapitalFlowKind.BUY,
                    tx.currency(), tx.quantity().multiply(tx.price()).add(fees).negate(), fees));
            case SELL -> List.of(new CapitalFlowEntry(
                    tx.id(), tx.ticker(), tx.transactionDate(), CapitalFlowKind.SELL,
                    tx.currency(), tx.quantity().multiply(tx.price()).subtract(fees), fees));
            case DIVIDEND -> List.of(new CapitalFlowEntry(
                    tx.id(), tx.ticker(), tx.transactionDate(), CapitalFlowKind.DIVIDEND,
                    tx.currency(), tx.quantity().multiply(tx.price()), fees));
            case SPLIT -> List.of();
        };
    }

    private static ApplyResult applyBuy(State state, Transaction tx) {
        if (invalidPositive(tx.quantity())) {
            return new ApplyResult.InvalidInput("Quantity must be positive");
        }
        if (invalidNonNegative(tx.price())) {
            return new ApplyResult.InvalidInput("Price cannot be negative");
        }
        BigDecimal fees = valueOrZero(tx.fees());
        BigDecimal transactionCost = tx.quantity().multiply(tx.price()).add(fees);
        BigDecimal newShares = valueOrZero(state.sharesOwned()).add(tx.quantity());
        BigDecimal newInvested = valueOrZero(state.totalInvestedAmount()).add(transactionCost);
        BigDecimal newFees = valueOrZero(state.totalTransactionFees()).add(fees);
        BigDecimal newAvg = averageCost(newInvested, newShares);
        LocalDate firstPurchase = state.firstPurchaseDate() != null
                ? state.firstPurchaseDate()
                : tx.transactionDate();
        State enriched = enrichMetadata(state, tx);
        return new ApplyResult.Success(new State(
                newShares, newAvg, newInvested, newFees,
                tx.currency() != null ? tx.currency() : enriched.currency(),
                firstPurchase,
                enriched.companyName(),
                enriched.exchange(),
                enriched.country()));
    }

    private static ApplyResult applySell(State state, Transaction tx) {
        if (invalidPositive(tx.quantity())) {
            return new ApplyResult.InvalidInput("Quantity must be positive");
        }
        if (invalidNonNegative(tx.price())) {
            return new ApplyResult.InvalidInput("Price cannot be negative");
        }
        BigDecimal available = valueOrZero(state.sharesOwned());
        if (available.compareTo(tx.quantity()) < 0) {
            return new ApplyResult.Oversell(tx.ticker(), tx.quantity(), available);
        }
        BigDecimal fees = valueOrZero(tx.fees());
        BigDecimal avg = valueOrZero(state.averageCostPerShare());
        BigDecimal newShares = available.subtract(tx.quantity());
        BigDecimal proportionalCost = tx.quantity().multiply(avg);
        BigDecimal newInvested = valueOrZero(state.totalInvestedAmount()).subtract(proportionalCost);
        if (newShares.compareTo(BigDecimal.ZERO) == 0) {
            newInvested = BigDecimal.ZERO;
        }
        BigDecimal newFees = valueOrZero(state.totalTransactionFees()).add(fees);
        BigDecimal newAvg = averageCost(newInvested, newShares);
        State enriched = enrichMetadata(state, tx);
        return new ApplyResult.Success(new State(
                newShares, newAvg, newInvested, newFees,
                enriched.currency(),
                state.firstPurchaseDate(),
                enriched.companyName(),
                enriched.exchange(),
                enriched.country()));
    }

    private static ApplyResult applySplit(State state, Transaction tx) {
        if (invalidPositive(tx.quantity())) {
            return new ApplyResult.InvalidInput("Split ratio must be positive");
        }
        if (!state.hasShares()) {
            return new ApplyResult.Success(enrichMetadata(state, tx));
        }
        BigDecimal ratio = tx.quantity();
        BigDecimal newShares = state.sharesOwned().multiply(ratio).setScale(6, RoundingMode.HALF_UP);
        BigDecimal newAvg = state.averageCostPerShare().divide(ratio, 6, RoundingMode.HALF_UP);
        State enriched = enrichMetadata(state, tx);
        return new ApplyResult.Success(new State(
                newShares, newAvg, state.totalInvestedAmount(), state.totalTransactionFees(),
                enriched.currency(),
                state.firstPurchaseDate(),
                enriched.companyName(),
                enriched.exchange(),
                enriched.country()));
    }

    private static State enrichMetadata(State state, Transaction tx) {
        return new State(
                state.sharesOwned(),
                state.averageCostPerShare(),
                state.totalInvestedAmount(),
                state.totalTransactionFees(),
                state.currency() != null ? state.currency() : tx.currency(),
                state.firstPurchaseDate(),
                firstNonBlank(tx.companyName(), state.companyName()),
                firstNonBlank(tx.exchange(), state.exchange()),
                firstNonBlank(tx.country(), state.country()));
    }

    private static Map<String, List<Transaction>> groupByTicker(List<Transaction> transactions) {
        return transactions.stream()
                .collect(Collectors.groupingBy(Transaction::ticker, LinkedHashMap::new, Collectors.toList()));
    }

    private static List<Transaction> chronological(List<Transaction> transactions) {
        return transactions.stream()
                .sorted(Comparator
                        .comparing(Transaction::transactionDate)
                        .thenComparing(tx -> tx.createdAt() != null ? tx.createdAt() : java.time.OffsetDateTime.MIN)
                        .thenComparing(tx -> tx.id() != null ? tx.id().toString() : ""))
                .toList();
    }

    private static BigDecimal averageCost(BigDecimal invested, BigDecimal shares) {
        return shares.compareTo(BigDecimal.ZERO) > 0
                ? invested.divide(shares, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    private static BigDecimal valueOrZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static boolean invalidPositive(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0;
    }

    private static boolean invalidNonNegative(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }
}

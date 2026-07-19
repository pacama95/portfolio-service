package com.portfolio.core.ports.outgoing;

import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionSortOrder;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public interface TransactionRepository {

    Uni<Transaction> save(Transaction transaction);

    Uni<Optional<Transaction>> findById(UserId userId, UUID id);

    Uni<List<Transaction>> findAll(UserId userId);

    /**
     * All transactions for all users — used to derive cross-user ledger state (e.g. which
     * tickers currently have an open position) rather than to serve any single user's view.
     */
    Uni<List<Transaction>> findAll();

    Uni<List<Transaction>> findByTicker(UserId userId, String ticker);

    Uni<List<Transaction>> search(
            UserId userId,
            String ticker,
            TransactionType type,
            LocalDate fromDate,
            LocalDate toDate,
            TransactionSortOrder sortOrder);

    Uni<Boolean> deleteById(UserId userId, UUID id);

    Uni<Long> count(UserId userId);

    Uni<Long> countByTicker(UserId userId, String ticker);

    /**
     * Acquires a transaction-scoped lock per (userId, ticker) pair — released automatically
     * when the transaction ends — then hands the caller each ticker's current transactions
     * (keyed by ticker) so it can validate a candidate change against a consistent view before
     * writing. Without this, a read-validate-write sequence spanning two separate reactive
     * sessions/transactions is a classic TOCTOU race: a concurrent write for the same ticker
     * between the read and the write would go unnoticed by the in-memory ledger replay.
     * Multiple tickers are always locked in a fixed (sorted) order to avoid deadlocking against
     * another call locking the same two tickers in the opposite order (e.g. two concurrent
     * ticker-renaming updates crossing each other).
     */
    <T> Uni<T> withTickersLocked(
            UserId userId, List<String> tickers, Function<Map<String, List<Transaction>>, Uni<T>> action);
}

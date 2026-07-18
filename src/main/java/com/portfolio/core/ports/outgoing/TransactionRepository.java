package com.portfolio.core.ports.outgoing;

import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionSortOrder;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository {

    Uni<Transaction> save(Transaction transaction);

    Uni<Optional<Transaction>> findById(UserId userId, UUID id);

    Uni<List<Transaction>> findAll(UserId userId);

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

    Uni<List<String>> findDistinctTickers();
}

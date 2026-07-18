package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionSortOrder;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.time.LocalDate;
import java.util.List;

public interface SearchTransactionsUseCase {

    Uni<Result> execute(Query query);

    sealed interface Result {
        record Success(List<Transaction> transactions) implements Result {}
    }

    record Query(
            UserId userId,
            String ticker,
            TransactionType type,
            LocalDate fromDate,
            LocalDate toDate,
            TransactionSortOrder sortOrder
    ) {
        public static Query all(UserId userId) {
            return new Query(userId, null, null, null, null, TransactionSortOrder.DESC);
        }

        public static Query byTicker(UserId userId, String ticker) {
            return new Query(userId, ticker, null, null, null, TransactionSortOrder.DESC);
        }
    }
}

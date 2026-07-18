package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

public interface CountTransactionsUseCase {

    Uni<Result> execute(Query query);

    sealed interface Result {
        record Success(long count) implements Result {}
    }

    record Query(UserId userId, String ticker) {
        public static Query all(UserId userId) {
            return new Query(userId, null);
        }

        public static Query byTicker(UserId userId, String ticker) {
            return new Query(userId, ticker);
        }
    }
}

package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.PortfolioSummary;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

public interface GetPortfolioSummaryUseCase {

    Uni<Result> execute(Query query);

    sealed interface Result {
        record Success(PortfolioSummary summary) implements Result {}
        record Conflict(String message) implements Result {}
    }

    record Query(UserId userId, boolean activeOnly) {
        public static Query all(UserId userId) {
            return new Query(userId, false);
        }

        public static Query active(UserId userId) {
            return new Query(userId, true);
        }
    }
}

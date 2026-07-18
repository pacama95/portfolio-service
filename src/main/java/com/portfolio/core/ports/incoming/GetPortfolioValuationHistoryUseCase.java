package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.DailyValuation;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.time.LocalDate;
import java.util.List;

public interface GetPortfolioValuationHistoryUseCase {

    Uni<Result> execute(Query query);

    sealed interface Result {
        record Success(List<DailyValuation> valuations) implements Result {}
        record InvalidRequest(String message) implements Result {}
    }

    record Query(UserId userId, LocalDate from, LocalDate to) {
    }
}

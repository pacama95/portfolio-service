package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.PerformanceInputs;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.time.LocalDate;

public interface GetPerformanceInputsUseCase {

    Uni<Result> execute(Query query);

    sealed interface Result {
        record Success(PerformanceInputs inputs) implements Result {}
        record InvalidRequest(String message) implements Result {}
    }

    record Query(UserId userId, LocalDate from, LocalDate to) {
    }
}

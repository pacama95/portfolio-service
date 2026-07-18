package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface GetPriceIngestionRunUseCase {

    Uni<Result> execute(Query query);

    sealed interface Result {
        record Success(RunStatus status) implements Result {}
        record NotFound() implements Result {}
    }

    record Query(UserId userId) {
    }

    record RunStatus(
            UUID id,
            String status,
            LocalDate fromDate,
            LocalDate toDate,
            String ticker,
            int symbolsRequested,
            int symbolsCompleted,
            String errorMessage,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt
    ) {
    }
}

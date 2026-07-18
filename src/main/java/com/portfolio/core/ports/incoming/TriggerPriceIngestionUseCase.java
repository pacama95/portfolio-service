package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.time.LocalDate;
import java.util.UUID;

public interface TriggerPriceIngestionUseCase {

    Uni<Result> execute(Command command);

    sealed interface Result {
        record Accepted(UUID runId) implements Result {}
        record Conflict(String message) implements Result {}
        record InvalidRequest(String message) implements Result {}
    }

    record Command(
            UserId userId,
            LocalDate from,
            LocalDate to,
            String ticker,
            boolean includeBackfill
    ) {
    }
}

package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@RegisterForReflection
public record IngestionRun(
        UUID id,
        UserId userId,
        Status status,
        LocalDate fromDate,
        LocalDate toDate,
        String ticker,
        boolean includeBackfill,
        int symbolsRequested,
        int symbolsCompleted,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    @RegisterForReflection
    public enum Status {
        PENDING, RUNNING, COMPLETED, PARTIAL, FAILED
    }
}

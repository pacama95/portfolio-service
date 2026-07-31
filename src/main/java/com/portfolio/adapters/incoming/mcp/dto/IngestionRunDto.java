package com.portfolio.adapters.incoming.mcp.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@RegisterForReflection
public record IngestionRunDto(
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

package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@RegisterForReflection
public record PriceHistoryEntry(
        String symbol,
        LocalDate priceDate,
        BigDecimal closePrice,
        BigDecimal adjustedClosePrice,
        Currency currency,
        OffsetDateTime fetchedAt
) {
}

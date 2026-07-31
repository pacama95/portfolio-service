package com.portfolio.adapters.incoming.mcp.dto;

import com.portfolio.core.model.Currency;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@RegisterForReflection
public record PriceHistoryEntryDto(
        String symbol,
        LocalDate priceDate,
        BigDecimal closePrice,
        BigDecimal adjustedClosePrice,
        Currency currency,
        OffsetDateTime fetchedAt
) {
}

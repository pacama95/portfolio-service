package com.portfolio.adapters.incoming.mcp.dto;

import com.portfolio.core.model.Currency;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;
import java.time.LocalDate;

@RegisterForReflection
public record DailyValuationDto(
        LocalDate date,
        Currency valuationCurrency,
        BigDecimal totalMarketValue,
        BigDecimal totalCost,
        boolean complete
) {
}

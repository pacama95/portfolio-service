package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.math.BigDecimal;
import java.time.LocalDate;

@RegisterForReflection
public record DailyValuation(
        LocalDate date,
        Currency valuationCurrency,
        BigDecimal totalMarketValue,
        BigDecimal totalCost,
        boolean complete
) {
}

package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.math.BigDecimal;
import java.time.LocalDate;

@RegisterForReflection
public record DailyPositionSnapshot(
        String ticker,
        LocalDate snapshotDate,
        BigDecimal sharesOwned,
        BigDecimal averageCostPerShare,
        BigDecimal totalInvestedAmount,
        Currency currency
) {
}

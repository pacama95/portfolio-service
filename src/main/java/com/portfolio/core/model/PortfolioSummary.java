package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.math.BigDecimal;
import java.math.RoundingMode;

@RegisterForReflection
public record PortfolioSummary(
        Currency valuationCurrency,
        BigDecimal totalMarketValue,
        BigDecimal totalCost,
        BigDecimal totalUnrealizedGainLoss,
        BigDecimal totalUnrealizedGainLossPercentage,
        int totalPositions,
        int activePositions,
        boolean complete
) {

    public static PortfolioSummary of(
            Currency valuationCurrency,
            BigDecimal totalMarketValue,
            BigDecimal totalCost,
            int totalPositions,
            int activePositions) {
        return of(valuationCurrency, totalMarketValue, totalCost, totalPositions, activePositions, true);
    }

    public static PortfolioSummary of(
            Currency valuationCurrency,
            BigDecimal totalMarketValue,
            BigDecimal totalCost,
            int totalPositions,
            int activePositions,
            boolean complete) {
        BigDecimal gainLoss = totalMarketValue.subtract(totalCost);
        BigDecimal percentage = totalCost.compareTo(BigDecimal.ZERO) > 0
                ? gainLoss.multiply(BigDecimal.valueOf(100)).divide(totalCost, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new PortfolioSummary(
                valuationCurrency, totalMarketValue, totalCost, gainLoss, percentage,
                totalPositions, activePositions, complete);
    }
}

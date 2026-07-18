package com.portfolio.adapters.incoming.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import com.portfolio.core.model.Currency;

import java.math.BigDecimal;

@RegisterForReflection
public record PortfolioSummaryResponse(
        Currency valuationCurrency,
        BigDecimal totalMarketValue,
        BigDecimal totalCost,
        BigDecimal totalUnrealizedGainLoss,
        BigDecimal totalUnrealizedGainLossPercentage,
        int totalPositions,
        int activePositions
) {
}

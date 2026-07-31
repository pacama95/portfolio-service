package com.portfolio.adapters.incoming.mcp.dto;

import com.portfolio.core.model.Currency;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;

@RegisterForReflection
public record PortfolioSummaryDto(
        Currency valuationCurrency,
        BigDecimal totalMarketValue,
        BigDecimal totalCost,
        BigDecimal totalUnrealizedGainLoss,
        BigDecimal totalUnrealizedGainLossPercentage,
        int totalPositions,
        int activePositions,
        boolean complete
) {
}

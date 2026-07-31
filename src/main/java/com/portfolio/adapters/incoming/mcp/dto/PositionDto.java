package com.portfolio.adapters.incoming.mcp.dto;

import com.portfolio.core.model.Currency;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;
import java.time.LocalDate;

@RegisterForReflection
public record PositionDto(
        String ticker,
        String companyName,
        BigDecimal totalQuantity,
        BigDecimal averagePrice,
        BigDecimal currentPrice,
        BigDecimal totalCost,
        Currency currency,
        LocalDate firstPurchaseDate,
        boolean isActive,
        BigDecimal marketValue,
        BigDecimal unrealizedGainLoss,
        BigDecimal unrealizedGainLossPercentage,
        String exchange,
        String country
) {
}

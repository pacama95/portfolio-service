package com.portfolio.adapters.incoming.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import com.portfolio.core.model.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

@RegisterForReflection
public record PositionResponse(
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

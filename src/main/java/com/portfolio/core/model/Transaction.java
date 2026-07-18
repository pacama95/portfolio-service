package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@RegisterForReflection
public record Transaction(
        UUID id,
        UserId userId,
        String ticker,
        TransactionType transactionType,
        AssetType assetType,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fees,
        Currency currency,
        LocalDate transactionDate,
        String notes,
        boolean fractional,
        BigDecimal fractionalMultiplier,
        Currency commissionCurrency,
        String exchange,
        String country,
        String companyName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public BigDecimal totalValue() {
        return quantity.multiply(price);
    }

    public BigDecimal totalCost() {
        BigDecimal feeAmount = fees != null ? fees : BigDecimal.ZERO;
        return totalValue().add(feeAmount);
    }
}

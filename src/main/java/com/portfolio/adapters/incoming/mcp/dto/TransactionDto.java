package com.portfolio.adapters.incoming.mcp.dto;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.TransactionType;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@RegisterForReflection
public record TransactionDto(
        UUID id,
        String ticker,
        TransactionType transactionType,
        AssetType assetType,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fees,
        Currency currency,
        LocalDate transactionDate,
        String notes,
        boolean isFractional,
        BigDecimal fractionalMultiplier,
        Currency commissionCurrency,
        String exchange,
        String country,
        String companyName,
        BigDecimal totalValue,
        BigDecimal totalCost,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

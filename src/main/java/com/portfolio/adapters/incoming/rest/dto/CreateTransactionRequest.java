package com.portfolio.adapters.incoming.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

@RegisterForReflection
public record CreateTransactionRequest(
        @NotBlank @Size(max = 20) String ticker,
        @NotNull TransactionType transactionType,
        @NotNull AssetType assetType,
        @NotNull @DecimalMin("0.000001") BigDecimal quantity,
        @NotNull @DecimalMin("0.0") BigDecimal price,
        BigDecimal fees,
        @NotNull Currency currency,
        @NotNull LocalDate transactionDate,
        String notes,
        Boolean isFractional,
        BigDecimal fractionalMultiplier,
        Currency commissionCurrency,
        @NotBlank @Size(max = 20) String exchange,
        @NotBlank @Size(max = 50) String country,
        String companyName
) {
}

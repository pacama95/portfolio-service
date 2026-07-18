package com.portfolio.adapters.incoming.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

@RegisterForReflection
public record UpdateTransactionRequest(
        @Size(max = 20) String ticker,
        TransactionType transactionType,
        @DecimalMin("0.000001") BigDecimal quantity,
        @DecimalMin("0.0") BigDecimal price,
        BigDecimal fees,
        Currency currency,
        LocalDate transactionDate,
        String notes,
        Boolean isFractional,
        BigDecimal fractionalMultiplier,
        Currency commissionCurrency,
        String exchange,
        String country,
        String companyName
) {
}

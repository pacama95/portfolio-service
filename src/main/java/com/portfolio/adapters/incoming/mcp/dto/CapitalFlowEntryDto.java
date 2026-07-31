package com.portfolio.adapters.incoming.mcp.dto;

import com.portfolio.core.model.CapitalFlowKind;
import com.portfolio.core.model.Currency;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RegisterForReflection
public record CapitalFlowEntryDto(
        UUID transactionId,
        String ticker,
        LocalDate flowDate,
        CapitalFlowKind kind,
        Currency currency,
        BigDecimal amount,
        BigDecimal fees
) {
}

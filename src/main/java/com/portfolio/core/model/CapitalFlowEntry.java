package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RegisterForReflection
public record CapitalFlowEntry(
        UUID transactionId,
        String ticker,
        LocalDate flowDate,
        CapitalFlowKind kind,
        Currency currency,
        BigDecimal amount,
        BigDecimal fees
) {
}

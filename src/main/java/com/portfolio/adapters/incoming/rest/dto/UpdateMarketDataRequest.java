package com.portfolio.adapters.incoming.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import com.portfolio.core.model.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@RegisterForReflection
public record UpdateMarketDataRequest(
        @NotNull @DecimalMin("0.0") BigDecimal price,
        Currency currency
) {
}

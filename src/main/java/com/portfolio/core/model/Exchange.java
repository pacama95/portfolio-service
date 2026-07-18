package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.math.BigDecimal;

@RegisterForReflection
public record Exchange(
        String code,
        String name,
        String country,
        String currency,
        String priceCurrency,
        BigDecimal priceMultiplier
) {
}

package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@RegisterForReflection
public record FxRateEntry(
        Currency baseCurrency,
        Currency quoteCurrency,
        LocalDate rateDate,
        BigDecimal rate,
        OffsetDateTime fetchedAt
) {
}

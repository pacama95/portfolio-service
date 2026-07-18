package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@RegisterForReflection
public record SpotQuote(
        SpotQuoteKind kind,
        String symbol,
        BigDecimal value,
        Currency currency,
        Currency baseCurrency,
        Currency quoteCurrency,
        OffsetDateTime asOf,
        QuoteSource source
) {
}

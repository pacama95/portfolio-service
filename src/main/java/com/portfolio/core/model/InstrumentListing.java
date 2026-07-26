package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Provider-neutral listing metadata known by the portfolio domain.
 *
 * <p>The exchange and country values intentionally retain the values supplied during
 * transaction ingestion. A market-data adapter is responsible for translating them to its
 * own identifiers.</p>
 */
@RegisterForReflection
public record InstrumentListing(
        String symbol,
        String exchange,
        String country,
        Currency currency
) {
}

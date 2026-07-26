package com.portfolio.core.model;

/** Internal routing result used to preserve provider provenance at persistence boundaries. */
public record MarketDataProviderResult<T>(String providerId, T value) {

    public MarketDataProviderResult {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
    }
}

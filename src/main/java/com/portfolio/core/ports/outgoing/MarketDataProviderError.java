package com.portfolio.core.ports.outgoing;

import java.time.Duration;

/**
 * Rollback-worthy failures from {@link MarketDataProviderPort}. These must fail the {@code Uni}
 * as exceptions (never be swallowed into a warning log) so callers never persist or record
 * coverage for data that was never actually returned.
 */
public sealed interface MarketDataProviderError {

    sealed abstract class ProviderException extends RuntimeException implements MarketDataProviderError {
        protected ProviderException(String message) {
            super(message);
        }
    }

    final class ErrorResponse extends ProviderException {
        public ErrorResponse(String symbol, String message) {
            super("Provider error response for " + symbol + ": " + message);
        }
    }

    final class MissingData extends ProviderException {
        public MissingData(String symbol, String field) {
            super("Provider response missing '" + field + "' for " + symbol);
        }
    }

    final class UnsupportedCurrency extends ProviderException {
        public UnsupportedCurrency(String symbol, String currencyCode) {
            super("Provider returned unsupported currency '" + currencyCode + "' for " + symbol);
        }
    }

    final class ProviderUnavailable extends ProviderException {
        public ProviderUnavailable(String symbol) {
            super("Market data provider unavailable for " + symbol);
        }
    }

    final class SymbolResolution extends ProviderException {
        public SymbolResolution(String symbol, String reason) {
            super("Unable to resolve market data symbol '" + symbol + "': " + reason);
        }
    }

    /**
     * The provider's credit budget is exhausted — either the client-side limiter's queue is
     * full, or the provider itself reported a rate-limit error. {@code retryAfter} is the
     * earliest point a retry has a chance of being served.
     */
    final class RateLimited extends ProviderException {
        private final Duration retryAfter;

        public RateLimited(String symbol, Duration retryAfter) {
            super("Provider rate limit exceeded for " + symbol
                    + "; retry after " + retryAfter.toSeconds() + "s");
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }
}

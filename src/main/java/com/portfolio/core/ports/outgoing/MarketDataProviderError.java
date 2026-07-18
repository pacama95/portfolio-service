package com.portfolio.core.ports.outgoing;

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
}

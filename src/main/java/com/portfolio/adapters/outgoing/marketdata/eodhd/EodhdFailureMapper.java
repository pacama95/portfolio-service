package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import java.time.Duration;

final class EodhdFailureMapper {

    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofMinutes(1);

    private EodhdFailureMapper() {
    }

    static Throwable translate(Throwable failure, String symbol) {
        if (failure instanceof MarketDataProviderError.ProviderException) {
            return failure;
        }
        if (failure instanceof WebApplicationException webFailure && webFailure.getResponse() != null) {
            int status = webFailure.getResponse().getStatus();
            if (status == 429) {
                return new MarketDataProviderError.RateLimited(symbol, retryAfter(webFailure));
            }
            if (status == 400 || status == 401 || status == 403 || status == 404) {
                return new MarketDataProviderError.ErrorResponse(symbol, "upstream HTTP " + status);
            }
            if (status >= 500) {
                return new MarketDataProviderError.ProviderUnavailable(symbol);
            }
        }
        if (failure instanceof ProcessingException) {
            return new MarketDataProviderError.ProviderUnavailable(symbol);
        }
        return failure;
    }

    private static Duration retryAfter(WebApplicationException failure) {
        String value = failure.getResponse().getHeaderString("Retry-After");
        if (value == null) {
            return DEFAULT_RETRY_AFTER;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? DEFAULT_RETRY_AFTER : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            return DEFAULT_RETRY_AFTER;
        }
    }
}

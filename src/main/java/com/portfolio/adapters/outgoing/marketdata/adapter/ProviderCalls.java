package com.portfolio.adapters.outgoing.marketdata.adapter;

import com.portfolio.adapters.common.ReactiveRateLimiter;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Shared plumbing for outgoing market-data calls: take a rate-limit slot, then translate whatever
 * the transport throws into a typed {@link MarketDataProviderError} so the router can decide
 * whether to fall back. Every provider needs both, and a provider that leaked a raw transport
 * exception would be treated as a code defect and stop the chain instead of failing over.
 */
public final class ProviderCalls {

    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofMinutes(1);

    private ProviderCalls() {
    }

    public static <T> Uni<T> throttled(
            ReactiveRateLimiter rateLimiter,
            String symbol,
            Supplier<Uni<T>> call) {
        return rateLimiter.acquire()
                .onFailure(ReactiveRateLimiter.RateLimitExceededException.class)
                .transform(failure -> new MarketDataProviderError.RateLimited(symbol, failure.retryAfter()))
                .chain(call::get)
                .onFailure().transform(failure -> translate(failure, symbol));
    }

    public static Throwable translate(Throwable failure, String symbol) {
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

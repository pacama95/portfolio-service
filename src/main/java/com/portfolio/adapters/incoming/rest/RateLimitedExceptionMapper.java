package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps an exhausted market-data credit budget to 503 + Retry-After, so callers see a clean,
 * retryable signal instead of the provider's raw error. Wins over {@link GlobalExceptionMapper}
 * because JAX-RS picks the mapper closest to the exception type.
 */
@Provider
public class RateLimitedExceptionMapper implements ExceptionMapper<MarketDataProviderError.RateLimited> {

    private static final Logger LOG = Logger.getLogger(RateLimitedExceptionMapper.class);

    @Override
    public Response toResponse(MarketDataProviderError.RateLimited exception) {
        String correlationId = CorrelationIds.currentTraceId();
        long retryAfterSeconds = Math.max(1, (exception.retryAfter().toNanos() + 999_999_999L) / 1_000_000_000L);
        LOG.warnf("Market data rate limited correlationId=%s retryAfterSeconds=%d",
                correlationId, retryAfterSeconds);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "RATE_LIMITED");
        body.put("message", "Market data provider budget exhausted; retry after "
                + retryAfterSeconds + " seconds");
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }

        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}

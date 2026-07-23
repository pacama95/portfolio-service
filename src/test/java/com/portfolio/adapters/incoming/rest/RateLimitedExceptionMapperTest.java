package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class RateLimitedExceptionMapperTest {

    private final RateLimitedExceptionMapper mapper = new RateLimitedExceptionMapper();

    @Test
    void givenRateLimited_whenToResponse_thenReturns503WithRetryAfter() {
        Response response = mapper.toResponse(
                new MarketDataProviderError.RateLimited("AAPL", Duration.ofSeconds(45)));

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus());
        assertEquals(45L, response.getHeaders().getFirst("Retry-After"));
        Map<?, ?> body = assertInstanceOf(Map.class, response.getEntity());
        assertEquals("RATE_LIMITED", body.get("error"));
        assertEquals("Market data provider budget exhausted; retry after 45 seconds",
                body.get("message"));
        // No active OTel span in unit tests
        assertNull(body.get("correlationId"));
    }

    @Test
    void givenSubSecondRetryAfter_whenToResponse_thenRoundsUpToOneSecond() {
        Response response = mapper.toResponse(
                new MarketDataProviderError.RateLimited("AAPL", Duration.ofMillis(200)));

        assertEquals(1L, response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void givenFractionalSeconds_whenToResponse_thenCeils() {
        Response response = mapper.toResponse(
                new MarketDataProviderError.RateLimited("AAPL", Duration.ofMillis(7500)));

        assertEquals(8L, response.getHeaders().getFirst("Retry-After"));
    }
}

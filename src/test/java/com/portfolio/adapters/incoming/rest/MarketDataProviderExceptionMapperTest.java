package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MarketDataProviderExceptionMapperTest {

    private final MarketDataProviderExceptionMapper mapper = new MarketDataProviderExceptionMapper();

    @Test
    void givenAllProvidersFailed_whenMapped_thenReturnsSanitized503() {
        MarketDataProviderError.AllProvidersFailed failure = new MarketDataProviderError.AllProvidersFailed(
                "AAPL",
                List.of(
                        new MarketDataProviderError.ErrorResponse("AAPL", "secret upstream diagnostic"),
                        new MarketDataProviderError.ProviderUnavailable("AAPL")));

        Response response = mapper.toResponse(failure);

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getEntity());
        assertEquals("MARKET_DATA_UNAVAILABLE", body.get("error"));
        assertEquals("Market data is temporarily unavailable", body.get("message"));
        assertFalse(body.toString().contains("secret upstream diagnostic"));
    }

    @Test
    void givenSingleTypedProviderFailure_whenMapped_thenReturns503() {
        Response response = mapper.toResponse(new MarketDataProviderError.MissingData("AAPL", "close"));

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus());
    }
}

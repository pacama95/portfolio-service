package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SymbolResolutionExceptionMapperTest {

    private final SymbolResolutionExceptionMapper mapper = new SymbolResolutionExceptionMapper();

    @Test
    void givenUnresolvedSymbol_whenToResponse_thenReturnsSanitizedActionableError() {
        Response response = mapper.toResponse(
                new MarketDataProviderError.SymbolResolution("AAPL", "provider-specific reason"));

        assertEquals(422, response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertEquals("MARKET_SYMBOL_UNRESOLVED", body.get("error"));
        assertEquals(
                "Market data symbol could not be resolved; verify ticker, exchange, and country",
                body.get("message"));
    }
}

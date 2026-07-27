package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps an unresolved canonical ticker to an actionable, provider-neutral client error. */
@Provider
public class SymbolResolutionExceptionMapper
        implements ExceptionMapper<MarketDataProviderError.SymbolResolution> {

    private static final Logger LOG = Logger.getLogger(SymbolResolutionExceptionMapper.class);

    @Override
    public Response toResponse(MarketDataProviderError.SymbolResolution exception) {
        String correlationId = CorrelationIds.currentTraceId();
        LOG.warnf("Market data symbol unresolved correlationId=%s", correlationId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "MARKET_SYMBOL_UNRESOLVED");
        body.put("message", "Market data symbol could not be resolved; verify ticker, exchange, and country");
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        return Response.status(422)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}

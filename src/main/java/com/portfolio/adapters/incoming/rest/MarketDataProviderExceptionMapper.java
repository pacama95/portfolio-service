package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/** Returns a sanitized retryable outage for exhausted typed provider failures. */
@Provider
public class MarketDataProviderExceptionMapper
        implements ExceptionMapper<MarketDataProviderError.ProviderException> {

    private static final Logger LOG = Logger.getLogger(MarketDataProviderExceptionMapper.class);

    @Override
    public Response toResponse(MarketDataProviderError.ProviderException exception) {
        String correlationId = CorrelationIds.currentTraceId();
        LOG.warnf("Market data unavailable correlationId=%s category=%s",
                correlationId, exception.getClass().getSimpleName());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "MARKET_DATA_UNAVAILABLE");
        body.put("message", "Market data is temporarily unavailable");
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}

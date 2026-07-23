package com.portfolio.adapters.incoming.rest;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catch-all for unexpected failures. Logs once at ERROR and returns a correlated 500 body.
 * More specific ExceptionMappers (validation, WebApplicationException) take precedence.
 */
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof WebApplicationException webApplicationException) {
            Response response = webApplicationException.getResponse();
            // An upstream provider's 429 must not leak to our clients as-is; present it as a
            // retryable outage of this service. Credits reset per minute, hence Retry-After 60.
            if (response != null && response.getStatus() == 429) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "RATE_LIMITED");
                body.put("message", "Upstream provider rate limit hit; retry after 60 seconds");
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.RETRY_AFTER, 60)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(body)
                        .build();
            }
            if (response != null && response.getStatus() < 500) {
                return response;
            }
        }

        String correlationId = CorrelationIds.currentTraceId();
        LOG.errorf(exception, "Unhandled exception correlationId=%s", correlationId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "INTERNAL_ERROR");
        body.put("message", "An unexpected error occurred");
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}

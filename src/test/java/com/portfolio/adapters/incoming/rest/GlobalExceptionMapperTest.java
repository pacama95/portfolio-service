package com.portfolio.adapters.incoming.rest;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionMapperTest {

    private final GlobalExceptionMapper mapper = new GlobalExceptionMapper();

    @Test
    void givenUnexpectedException_whenToResponse_thenReturns500WithInternalError() {
        Response response = mapper.toResponse(new IllegalStateException("boom"));

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getEntity());
        assertEquals("INTERNAL_ERROR", body.get("error"));
        assertEquals("An unexpected error occurred", body.get("message"));
        // No active OTel span in unit tests
        assertNull(body.get("correlationId"));
    }

    @Test
    void givenClientWebApplicationException_whenToResponse_thenPreservesOriginalResponse() {
        Response original = Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "BAD_REQUEST"))
                .build();

        Response response = mapper.toResponse(new WebApplicationException(original));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getEntity());
        assertEquals("BAD_REQUEST", body.get("error"));
    }

    @Test
    void givenUpstream429WebApplicationException_whenToResponse_thenMapsTo503WithRetryAfter() {
        Response original = Response.status(429).entity("{\"raw\":\"provider body\"}").build();

        Response response = mapper.toResponse(new WebApplicationException(original));

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), response.getStatus());
        assertEquals(60, response.getHeaders().getFirst("Retry-After"));
        Map<?, ?> body = assertInstanceOf(Map.class, response.getEntity());
        assertEquals("RATE_LIMITED", body.get("error"));
    }

    @Test
    void givenServerWebApplicationException_whenToResponse_thenMapsToInternalError() {
        Response original = Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();

        Response response = mapper.toResponse(new WebApplicationException(original));

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getEntity());
        assertEquals("INTERNAL_ERROR", body.get("error"));
    }
}

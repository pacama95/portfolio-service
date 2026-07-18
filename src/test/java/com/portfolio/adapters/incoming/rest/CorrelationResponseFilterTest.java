package com.portfolio.adapters.incoming.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

class CorrelationResponseFilterTest {

    private CorrelationResponseFilter filter;
    private ContainerRequestContext requestContext;
    private ContainerResponseContext responseContext;
    private MultivaluedMap<String, Object> headers;

    @BeforeEach
    void setUp() {
        MDC.clear();
        filter = new CorrelationResponseFilter();
        requestContext = Mockito.mock(ContainerRequestContext.class);
        responseContext = Mockito.mock(ContainerResponseContext.class);
        headers = new MultivaluedHashMap<>();
        when(responseContext.getHeaders()).thenReturn(headers);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void givenUserIdInMdc_whenFilter_thenRemovesUserIdFromMdc() {
        MDC.put(CorrelationIds.USER_ID_MDC_KEY, "user-42");

        filter.filter(requestContext, responseContext);

        assertNull(MDC.get(CorrelationIds.USER_ID_MDC_KEY));
    }

    @Test
    void givenNoActiveSpan_whenFilter_thenDoesNotSetRequestIdHeader() {
        filter.filter(requestContext, responseContext);

        assertFalse(headers.containsKey(CorrelationIds.REQUEST_ID_HEADER));
    }
}

package com.portfolio.adapters.incoming.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;

@Provider
public class CorrelationResponseFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String traceId = CorrelationIds.currentTraceId();
        if (traceId != null) {
            responseContext.getHeaders().putSingle(CorrelationIds.REQUEST_ID_HEADER, traceId);
        }
        MDC.remove(CorrelationIds.USER_ID_MDC_KEY);
    }
}

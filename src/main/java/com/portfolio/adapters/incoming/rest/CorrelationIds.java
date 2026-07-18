package com.portfolio.adapters.incoming.rest;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * Helpers for reading the current request correlation id (OpenTelemetry traceId).
 */
public final class CorrelationIds {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String USER_ID_MDC_KEY = "userId";

    private CorrelationIds() {
    }

    public static String currentTraceId() {
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            return spanContext.getTraceId();
        }
        return null;
    }
}

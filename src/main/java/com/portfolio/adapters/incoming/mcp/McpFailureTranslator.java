package com.portfolio.adapters.incoming.mcp;

import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.quarkiverse.mcp.server.ToolCallException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Outermost MCP boundary sanitizer: translates rollback-worthy {@code Error} failures (already
 * propagated through Mutiny's failure channel by lower layers, rollback done) into a
 * {@link ToolCallException} carrying a stable error code, mirroring the REST layer's
 * ExceptionMapper set. A {@link ToolCallException} thrown by a tool's own Result-to-DTO mapping
 * is a deliberate business rejection and passes through unchanged.
 */
@ApplicationScoped
public class McpFailureTranslator {

    private static final Logger LOG = Logger.getLogger(McpFailureTranslator.class);

    public <T> Uni<T> sanitize(Uni<T> pipeline) {
        return pipeline.onFailure().transform(this::translate);
    }

    private Throwable translate(Throwable failure) {
        return switch (failure) {
            case ToolCallException toolCallException -> toolCallException;
            case MarketDataProviderError.RateLimited rateLimited -> rateLimited(rateLimited);
            case MarketDataProviderError.SymbolResolution symbolResolution -> symbolResolution(symbolResolution);
            case MarketDataProviderError.ProviderException providerException -> providerUnavailable(providerException);
            default -> internalError(failure);
        };
    }

    private ToolCallException rateLimited(MarketDataProviderError.RateLimited failure) {
        long retryAfterSeconds = Math.max(1,
                (failure.retryAfter().toNanos() + 999_999_999L) / 1_000_000_000L);
        LOG.warnf("Market data rate limited correlationId=%s retryAfterSeconds=%d",
                currentTraceId(), retryAfterSeconds);
        return new ToolCallException("RATE_LIMITED: market data provider budget exhausted; retry after "
                + retryAfterSeconds + " seconds");
    }

    private ToolCallException symbolResolution(MarketDataProviderError.SymbolResolution failure) {
        LOG.warnf("Market data symbol unresolved correlationId=%s", currentTraceId());
        return new ToolCallException(
                "MARKET_SYMBOL_UNRESOLVED: market data symbol could not be resolved; verify ticker, exchange, and country");
    }

    private ToolCallException providerUnavailable(MarketDataProviderError.ProviderException failure) {
        LOG.warnf("Market data unavailable correlationId=%s category=%s",
                currentTraceId(), failure.getClass().getSimpleName());
        return new ToolCallException("MARKET_DATA_UNAVAILABLE: market data is temporarily unavailable");
    }

    private ToolCallException internalError(Throwable failure) {
        String correlationId = currentTraceId();
        LOG.errorf(failure, "Unhandled MCP tool failure correlationId=%s", correlationId);
        String suffix = correlationId != null ? " correlationId=" + correlationId : "";
        return new ToolCallException("INTERNAL_ERROR: an unexpected error occurred" + suffix);
    }

    private static String currentTraceId() {
        SpanContext spanContext = Span.current().getSpanContext();
        return spanContext.isValid() ? spanContext.getTraceId() : null;
    }
}

package com.portfolio.adapters.incoming.mcp;

import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import io.quarkiverse.mcp.server.ToolCallException;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpFailureTranslatorTest {

    private final McpFailureTranslator translator = new McpFailureTranslator();

    @Test
    void givenSuccessfulPipeline_whenSanitize_thenPassesItemThrough() {
        String item = translator.sanitize(Uni.createFrom().item("ok"))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals("ok", item);
    }

    @Test
    void givenToolCallException_whenSanitize_thenPassesThroughUnchanged() {
        ToolCallException original = new ToolCallException("INVALID_REQUEST: bad quantity");

        Throwable failure = translator.sanitize(Uni.createFrom().<String>failure(original))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals(original, failure);
    }

    @Test
    void givenRateLimited_whenSanitize_thenFailsWithRateLimitedCodeAndRetryAfter() {
        Throwable failure = translator.sanitize(Uni.createFrom().<String>failure(
                        new MarketDataProviderError.RateLimited("AAPL", Duration.ofSeconds(45))))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        ToolCallException toolCallException = (ToolCallException) failure;
        assertTrue(toolCallException.getMessage().startsWith("RATE_LIMITED"));
        assertTrue(toolCallException.getMessage().contains("45 seconds"));
    }

    @Test
    void givenSubSecondRetryAfter_whenSanitize_thenRoundsUpToOneSecond() {
        Throwable failure = translator.sanitize(Uni.createFrom().<String>failure(
                        new MarketDataProviderError.RateLimited("AAPL", Duration.ofMillis(200))))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure.getMessage().contains("1 seconds"));
    }

    @Test
    void givenSymbolResolutionFailure_whenSanitize_thenFailsWithMarketSymbolUnresolvedCode() {
        Throwable failure = translator.sanitize(Uni.createFrom().<String>failure(
                        new MarketDataProviderError.SymbolResolution("AAPL", "no exchange match")))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure.getMessage().startsWith("MARKET_SYMBOL_UNRESOLVED"));
        assertFalse(failure.getMessage().contains("no exchange match"));
    }

    @Test
    void givenOtherProviderException_whenSanitize_thenFailsWithMarketDataUnavailableCode() {
        Throwable failure = translator.sanitize(Uni.createFrom().<String>failure(
                        new MarketDataProviderError.ProviderUnavailable("AAPL")))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure.getMessage().startsWith("MARKET_DATA_UNAVAILABLE"));
        assertFalse(failure.getMessage().contains("AAPL"));
    }

    @Test
    void givenUnexpectedFailure_whenSanitize_thenFailsWithInternalErrorCodeAndNoLeakedDetail() {
        Throwable failure = translator.sanitize(Uni.createFrom().<String>failure(
                        new IllegalStateException("connection pool exhausted at 10.0.0.5")))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure.getMessage().startsWith("INTERNAL_ERROR"));
        assertFalse(failure.getMessage().contains("10.0.0.5"));
        assertFalse(failure.getMessage().contains("connection pool"));
    }
}

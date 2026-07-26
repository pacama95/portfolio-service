package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class EodhdFailureMapperTest {

    @Test
    void givenHttp429_whenTranslated_thenPreservesRetryAfter() {
        Response response = Response.status(429).header("Retry-After", "12").build();

        MarketDataProviderError.RateLimited result = assertInstanceOf(
                MarketDataProviderError.RateLimited.class,
                EodhdFailureMapper.translate(new WebApplicationException(response), "AAPL"));

        assertEquals(Duration.ofSeconds(12), result.retryAfter());
    }

    @Test
    void givenHttpClientError_whenTranslated_thenReturnsSanitizedProviderError() {
        for (int status : new int[]{400, 401, 403, 404}) {
            Throwable result = EodhdFailureMapper.translate(
                    new WebApplicationException(Response.status(status).build()), "AAPL");

            assertInstanceOf(MarketDataProviderError.ErrorResponse.class, result);
        }
    }

    @Test
    void givenHttpExceptionWithoutResponse_whenTranslated_thenLeavesFailureUntouched() {
        WebApplicationException failure = org.mockito.Mockito.mock(WebApplicationException.class);

        assertSame(failure, EodhdFailureMapper.translate(failure, "AAPL"));
    }

    @Test
    void givenHttpServerError_whenTranslated_thenReturnsUnavailable() {
        Throwable result = EodhdFailureMapper.translate(
                new WebApplicationException(Response.status(503).build()), "AAPL");

        assertInstanceOf(MarketDataProviderError.ProviderUnavailable.class, result);
    }

    @Test
    void givenUnhandledHttpStatus_whenTranslated_thenLeavesFailureUntouched() {
        WebApplicationException failure = new WebApplicationException(Response.status(418).build());

        assertSame(failure, EodhdFailureMapper.translate(failure, "AAPL"));
    }

    @Test
    void givenMissingInvalidOrNegativeRetryAfter_whenTranslated_thenUsesDefault() {
        for (String retryAfter : new String[]{null, "tomorrow", "-1"}) {
            Response.ResponseBuilder response = Response.status(429);
            if (retryAfter != null) {
                response.header("Retry-After", retryAfter);
            }
            MarketDataProviderError.RateLimited result = assertInstanceOf(
                    MarketDataProviderError.RateLimited.class,
                    EodhdFailureMapper.translate(new WebApplicationException(response.build()), "AAPL"));
            assertEquals(Duration.ofMinutes(1), result.retryAfter());
        }
    }

    @Test
    void givenAlreadyTypedFailure_whenTranslated_thenReturnsSameInstance() {
        MarketDataProviderError.MissingData failure = new MarketDataProviderError.MissingData("AAPL", "close");

        assertSame(failure, EodhdFailureMapper.translate(failure, "AAPL"));
    }

    @Test
    void givenTransportFailure_whenTranslated_thenReturnsUnavailable() {
        Throwable result = EodhdFailureMapper.translate(new ProcessingException("timeout"), "AAPL");

        assertInstanceOf(MarketDataProviderError.ProviderUnavailable.class, result);
    }

    @Test
    void givenUnexpectedFailure_whenTranslated_thenLeavesItUntouched() {
        IllegalStateException failure = new IllegalStateException("bug");

        assertSame(failure, EodhdFailureMapper.translate(failure, "AAPL"));
    }
}

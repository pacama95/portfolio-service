package com.portfolio.adapters.outgoing.marketdata.client;

/**
 * Common error envelope on every Twelve Data response. The API reports failures (rate limits,
 * invalid symbols, bad API key) as HTTP 200 with {@code status=error} plus {@code code} and
 * {@code message}, so every response DTO exposes these fields for a shared check.
 */
public interface TwelveDataResponse {

    String status();

    Integer code();

    String message();

    default boolean isError() {
        return "error".equalsIgnoreCase(status());
    }
}

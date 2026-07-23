package com.portfolio.adapters.outgoing.marketdata.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** {@code /exchange_rate} response; {@code rate} arrives as a JSON number, coerced to String. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record TwelveDataExchangeRateResponse(
        String rate,
        String status,
        Integer code,
        String message) implements TwelveDataResponse {
}

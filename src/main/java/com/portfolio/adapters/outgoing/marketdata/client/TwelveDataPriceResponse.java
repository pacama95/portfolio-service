package com.portfolio.adapters.outgoing.marketdata.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** {@code /price} response; {@code price} is quoted as a string by the API. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record TwelveDataPriceResponse(
        String price,
        String status,
        Integer code,
        String message) implements TwelveDataResponse {
}

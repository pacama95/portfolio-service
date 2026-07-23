package com.portfolio.adapters.outgoing.marketdata.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** {@code /time_series} response; numeric bar fields are quoted as strings by the API. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record TwelveDataTimeSeriesResponse(
        Meta meta,
        List<Bar> values,
        String status,
        Integer code,
        String message) implements TwelveDataResponse {

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(String currency) {
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bar(String datetime, String close) {
    }
}

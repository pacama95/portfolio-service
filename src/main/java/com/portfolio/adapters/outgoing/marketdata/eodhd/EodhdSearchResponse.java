package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record EodhdSearchResponse(
        @JsonProperty("Code") String code,
        @JsonProperty("Exchange") String exchange,
        @JsonProperty("Name") String name,
        @JsonProperty("Type") String type,
        @JsonProperty("Country") String country,
        @JsonProperty("Currency") String currency,
        @JsonProperty("ISIN") String isin,
        @JsonProperty("isPrimary") Boolean primary
) {
}

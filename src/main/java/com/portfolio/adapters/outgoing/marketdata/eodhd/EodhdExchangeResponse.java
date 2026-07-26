package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record EodhdExchangeResponse(
        @JsonProperty("Name") String name,
        @JsonProperty("Code") String code,
        @JsonProperty("OperatingMIC") String operatingMic,
        @JsonProperty("Country") String country,
        @JsonProperty("Currency") String currency,
        @JsonProperty("CountryISO2") String countryIso2,
        @JsonProperty("CountryISO3") String countryIso3
) {
}

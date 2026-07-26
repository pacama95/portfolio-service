package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record EodhdRealTimeResponse(
        String code,
        Long timestamp,
        @JsonDeserialize(using = EodhdDecimalDeserializer.class) BigDecimal close
) {
}

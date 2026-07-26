package com.portfolio.adapters.outgoing.marketdata.eodhd;

import java.time.OffsetDateTime;

record EodhdExchange(
        String code,
        String name,
        String operatingMic,
        String country,
        String currency,
        String countryIso2,
        String countryIso3,
        boolean active,
        OffsetDateTime fetchedAt
) {
}

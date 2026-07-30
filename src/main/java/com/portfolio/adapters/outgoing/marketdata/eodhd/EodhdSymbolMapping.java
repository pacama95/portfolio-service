package com.portfolio.adapters.outgoing.marketdata.eodhd;

import java.time.OffsetDateTime;

record EodhdSymbolMapping(
        String canonicalSymbol,
        String providerSymbol,
        String exchangeCode,
        String rawCurrency,
        EodhdResolutionSource resolutionSource,
        OffsetDateTime resolvedAt
) {
}

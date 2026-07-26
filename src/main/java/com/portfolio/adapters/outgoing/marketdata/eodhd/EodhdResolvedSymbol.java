package com.portfolio.adapters.outgoing.marketdata.eodhd;

record EodhdResolvedSymbol(
        String canonicalSymbol,
        String providerSymbol,
        String exchangeCode,
        String rawCurrency
) {
}

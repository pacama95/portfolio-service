package com.portfolio.adapters.outgoing.marketdata.eodhd;

enum EodhdResolutionSource {
    /** Inserted by an operator. Never written by the resolver and never invalidated. */
    MANUAL,
    TRANSACTION_METADATA,
    UNIQUE_SEARCH
}

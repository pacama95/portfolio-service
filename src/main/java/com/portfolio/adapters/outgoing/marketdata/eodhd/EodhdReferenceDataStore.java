package com.portfolio.adapters.outgoing.marketdata.eodhd;

import io.smallrye.mutiny.Uni;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

interface EodhdReferenceDataStore {

    Uni<List<EodhdExchange>> findExchanges();

    Uni<Optional<OffsetDateTime>> findLatestExchangeFetchTime();

    Uni<Void> replaceExchanges(List<EodhdExchange> exchanges, OffsetDateTime fetchedAt);

    Uni<Optional<EodhdSymbolMapping>> findSymbolMapping(String canonicalSymbol);

    Uni<Void> upsertSymbolMapping(EodhdSymbolMapping mapping);
}

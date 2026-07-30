package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.portfolio.core.ports.outgoing.ProviderSymbolMappingPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Adapter side of {@link ProviderSymbolMappingPort}; keeps EODHD naming out of the core. */
@ApplicationScoped
public class EodhdSymbolMappingInvalidator implements ProviderSymbolMappingPort {

    private final EodhdReferenceDataStore store;

    @Inject
    EodhdSymbolMappingInvalidator(EodhdReferenceDataStore store) {
        this.store = store;
    }

    @Override
    public Uni<Void> invalidate(String canonicalSymbol) {
        return store.deleteResolvedSymbolMapping(canonicalSymbol);
    }
}

package com.portfolio.adapters.outgoing.persistence.adapter;

import com.portfolio.adapters.outgoing.persistence.entity.TransactionEntity;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.InstrumentListing;
import com.portfolio.core.ports.outgoing.InstrumentListingPort;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class InstrumentListingStoreAdapter implements InstrumentListingPort {

    @Override
    @WithSession
    public Uni<List<InstrumentListing>> findBySymbol(String symbol) {
        String canonicalSymbol = normalize(symbol);
        return Panache.getSession().flatMap(session -> session.createQuery(
                        "select distinct t.exchange, t.country, t.currency "
                                + "from TransactionEntity t where upper(t.ticker) = :symbol",
                        Object[].class)
                .setParameter("symbol", canonicalSymbol)
                .getResultList())
                .map(rows -> rows.stream()
                        .map(row -> new InstrumentListing(
                                canonicalSymbol,
                                (String) row[0],
                                (String) row[1],
                                Currency.valueOf(((TransactionEntity.CurrencyDb) row[2]).name())))
                        .toList());
    }

    private String normalize(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}

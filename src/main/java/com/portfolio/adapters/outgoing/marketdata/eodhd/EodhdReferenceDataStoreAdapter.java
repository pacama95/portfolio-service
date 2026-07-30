package com.portfolio.adapters.outgoing.marketdata.eodhd;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@ApplicationScoped
class EodhdReferenceDataStoreAdapter implements EodhdReferenceDataStore {

    @Override
    @WithSession
    public Uni<List<EodhdExchange>> findExchanges() {
        return Panache.getSession().flatMap(session -> session
                .createQuery("from EodhdExchangeEntity e order by e.code", EodhdExchangeEntity.class)
                .getResultList())
                .map(entities -> entities.stream().map(this::toDomain).toList());
    }

    @Override
    @WithSession
    public Uni<Optional<OffsetDateTime>> findLatestExchangeFetchTime() {
        return Panache.getSession().flatMap(session -> session
                .createQuery("select max(e.fetchedAt) from EodhdExchangeEntity e", OffsetDateTime.class)
                .getSingleResultOrNull())
                .map(Optional::ofNullable);
    }

    @Override
    @WithTransaction
    public Uni<Void> replaceExchanges(List<EodhdExchange> exchanges, OffsetDateTime fetchedAt) {
        if (exchanges == null || exchanges.isEmpty()) {
            return Uni.createFrom().failure(new IllegalArgumentException("EODHD exchange snapshot must not be empty"));
        }
        if (fetchedAt == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("fetchedAt must not be null"));
        }
        StringBuilder sql = new StringBuilder(
                "insert into eodhd_exchanges "
                        + "(code, name, operating_mic, country, currency, country_iso2, country_iso3, "
                        + "active, fetched_at, updated_at) values ");
        for (int i = 0; i < exchanges.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(:code").append(i).append(", :name").append(i).append(", :mic").append(i)
                    .append(", :country").append(i).append(", :currency").append(i)
                    .append(", :iso2").append(i).append(", :iso3").append(i)
                    .append(", true, :fetchedAt, :fetchedAt)");
        }
        sql.append(" on conflict (code) do update set "
                + "name = excluded.name, "
                + "operating_mic = excluded.operating_mic, "
                + "country = excluded.country, "
                + "currency = excluded.currency, "
                + "country_iso2 = excluded.country_iso2, "
                + "country_iso3 = excluded.country_iso3, "
                + "active = true, "
                + "fetched_at = excluded.fetched_at, "
                + "updated_at = excluded.updated_at");

        return Panache.getSession().flatMap(session -> session.createQuery(
                        "update EodhdExchangeEntity e set e.active = false, e.updatedAt = :updatedAt")
                .setParameter("updatedAt", fetchedAt)
                .executeUpdate()
                .chain(() -> {
                    var query = session.createNativeQuery(sql.toString());
                    for (int i = 0; i < exchanges.size(); i++) {
                        EodhdExchange exchange = exchanges.get(i);
                        query.setParameter("code" + i, normalizeSymbol(exchange.code()));
                        query.setParameter("name" + i, exchange.name());
                        query.setParameter("mic" + i, exchange.operatingMic());
                        query.setParameter("country" + i, exchange.country());
                        query.setParameter("currency" + i, exchange.currency());
                        query.setParameter("iso2" + i, exchange.countryIso2());
                        query.setParameter("iso3" + i, exchange.countryIso3());
                    }
                    return query.setParameter("fetchedAt", fetchedAt).executeUpdate();
                }))
                .replaceWithVoid();
    }

    @Override
    @WithSession
    public Uni<Optional<EodhdSymbolMapping>> findSymbolMapping(String canonicalSymbol) {
        String normalized = normalizeSymbol(canonicalSymbol);
        return Panache.getSession().flatMap(session -> session
                .createQuery(
                        "from EodhdSymbolMappingEntity m where m.canonicalSymbol = :symbol",
                        EodhdSymbolMappingEntity.class)
                .setParameter("symbol", normalized)
                .getSingleResultOrNull())
                .map(entity -> Optional.ofNullable(entity).map(this::toDomain));
    }

    @Override
    @WithTransaction
    public Uni<Void> upsertSymbolMapping(EodhdSymbolMapping mapping) {
        if (mapping == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("mapping must not be null"));
        }
        OffsetDateTime now = OffsetDateTime.now();
        EodhdSymbolMappingEntity entity = new EodhdSymbolMappingEntity();
        entity.canonicalSymbol = normalizeSymbol(mapping.canonicalSymbol());
        entity.providerSymbol = mapping.providerSymbol();
        entity.exchangeCode = mapping.exchangeCode();
        entity.rawCurrency = mapping.rawCurrency();
        entity.resolutionSource = mapping.resolutionSource();
        entity.resolvedAt = mapping.resolvedAt();
        entity.updatedAt = now;
        return Panache.getSession().flatMap(session -> session.merge(entity)).replaceWithVoid();
    }

    @Override
    @WithTransaction
    public Uni<Void> deleteResolvedSymbolMapping(String canonicalSymbol) {
        return Panache.getSession().flatMap(session -> session.createQuery(
                        "delete from EodhdSymbolMappingEntity m where m.canonicalSymbol = :symbol "
                                + "and m.resolutionSource <> :manual")
                .setParameter("symbol", normalizeSymbol(canonicalSymbol))
                .setParameter("manual", EodhdResolutionSource.MANUAL)
                .executeUpdate())
                .replaceWithVoid();
    }

    private EodhdExchange toDomain(EodhdExchangeEntity entity) {
        return new EodhdExchange(
                entity.code,
                entity.name,
                entity.operatingMic,
                entity.country,
                entity.currency,
                entity.countryIso2,
                entity.countryIso3,
                entity.active,
                entity.fetchedAt);
    }

    private EodhdSymbolMapping toDomain(EodhdSymbolMappingEntity entity) {
        return new EodhdSymbolMapping(
                entity.canonicalSymbol,
                entity.providerSymbol,
                entity.exchangeCode,
                entity.rawCurrency,
                entity.resolutionSource,
                entity.resolvedAt);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("canonical symbol must not be blank");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}

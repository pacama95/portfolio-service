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
        return Panache.getSession().flatMap(session -> {
            Uni<Void> writes = session.createQuery(
                            "update EodhdExchangeEntity e set e.active = false, e.updatedAt = :updatedAt")
                    .setParameter("updatedAt", fetchedAt)
                    .executeUpdate()
                    .replaceWithVoid();
            for (EodhdExchange exchange : exchanges) {
                EodhdExchangeEntity entity = toEntity(exchange, fetchedAt);
                writes = writes.chain(() -> session.merge(entity).replaceWithVoid());
            }
            return writes;
        });
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
        entity.metadataFingerprint = mapping.metadataFingerprint();
        entity.resolutionSource = mapping.resolutionSource();
        entity.resolvedAt = mapping.resolvedAt();
        entity.updatedAt = now;
        return Panache.getSession().flatMap(session -> session.merge(entity)).replaceWithVoid();
    }

    private EodhdExchangeEntity toEntity(EodhdExchange exchange, OffsetDateTime fetchedAt) {
        EodhdExchangeEntity entity = new EodhdExchangeEntity();
        entity.code = exchange.code().trim().toUpperCase(Locale.ROOT);
        entity.name = exchange.name();
        entity.operatingMic = exchange.operatingMic();
        entity.country = exchange.country();
        entity.currency = exchange.currency();
        entity.countryIso2 = exchange.countryIso2();
        entity.countryIso3 = exchange.countryIso3();
        entity.active = true;
        entity.fetchedAt = fetchedAt;
        entity.updatedAt = fetchedAt;
        return entity;
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
                entity.metadataFingerprint,
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

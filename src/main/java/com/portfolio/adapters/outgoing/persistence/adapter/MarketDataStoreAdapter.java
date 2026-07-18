package com.portfolio.adapters.outgoing.persistence.adapter;

import com.portfolio.adapters.outgoing.persistence.entity.FxRateHistoryEntity;
import com.portfolio.adapters.outgoing.persistence.entity.MarketDataCoverageEntity;
import com.portfolio.adapters.outgoing.persistence.entity.PriceHistoryEntity;
import com.portfolio.adapters.outgoing.persistence.entity.SpotQuoteEntity;
import com.portfolio.adapters.outgoing.persistence.entity.TransactionEntity;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.QuoteSource;
import com.portfolio.core.model.SpotQuote;
import com.portfolio.core.model.SpotQuoteKind;
import com.portfolio.core.ports.outgoing.MarketDataStorePort;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class MarketDataStoreAdapter implements MarketDataStorePort {

    @Override
    @WithSession
    public Uni<List<PriceHistoryEntry>> findPrices(String symbol, LocalDate from, LocalDate to) {
        return Panache.getSession().flatMap(session ->
                session.createQuery(
                                "from PriceHistoryEntity p where p.symbol = :symbol "
                                        + "and p.priceDate >= :from and p.priceDate <= :to order by p.priceDate",
                                PriceHistoryEntity.class)
                        .setParameter("symbol", symbol)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getResultList())
                .map(list -> list.stream().map(this::toPriceEntry).toList());
    }

    @Override
    @WithTransaction
    public Uni<Void> upsertPrices(List<PriceHistoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return Panache.getSession().flatMap(session -> {
            Uni<Void> chain = Uni.createFrom().voidItem();
            for (PriceHistoryEntry entry : entries) {
                chain = chain.chain(() -> session.createQuery(
                                "from PriceHistoryEntity p where p.symbol = :symbol and p.priceDate = :date",
                                PriceHistoryEntity.class)
                        .setParameter("symbol", entry.symbol())
                        .setParameter("date", entry.priceDate())
                        .getSingleResultOrNull()
                        .flatMap(existing -> {
                            OffsetDateTime now = OffsetDateTime.now();
                            PriceHistoryEntity entity = existing != null ? existing : new PriceHistoryEntity();
                            if (entity.id == null) {
                                entity.id = UUID.randomUUID();
                                entity.createdAt = now;
                            }
                            entity.symbol = entry.symbol();
                            entity.priceDate = entry.priceDate();
                            entity.closePrice = entry.closePrice();
                            entity.adjustedClosePrice = entry.adjustedClosePrice();
                            entity.currency = TransactionEntity.CurrencyDb.valueOf(entry.currency().name());
                            entity.provider = "twelvedata";
                            entity.fetchedAt = entry.fetchedAt() != null ? entry.fetchedAt() : now;
                            entity.updatedAt = now;
                            return session.merge(entity).replaceWithVoid();
                        }));
            }
            return chain;
        });
    }

    @Override
    @WithSession
    public Uni<List<FxRateEntry>> findFxRates(Currency base, Currency quote, LocalDate from, LocalDate to) {
        return Panache.getSession().flatMap(session ->
                session.createQuery(
                                "from FxRateHistoryEntity f where f.baseCurrency = :base and f.quoteCurrency = :quote "
                                        + "and f.rateDate >= :from and f.rateDate <= :to order by f.rateDate",
                                FxRateHistoryEntity.class)
                        .setParameter("base", TransactionEntity.CurrencyDb.valueOf(base.name()))
                        .setParameter("quote", TransactionEntity.CurrencyDb.valueOf(quote.name()))
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getResultList())
                .map(list -> list.stream().map(this::toFxEntry).toList());
    }

    @Override
    @WithTransaction
    public Uni<Void> upsertFxRates(List<FxRateEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return Panache.getSession().flatMap(session -> {
            Uni<Void> chain = Uni.createFrom().voidItem();
            for (FxRateEntry entry : entries) {
                chain = chain.chain(() -> session.createQuery(
                                "from FxRateHistoryEntity f where f.baseCurrency = :base and f.quoteCurrency = :quote "
                                        + "and f.rateDate = :date",
                                FxRateHistoryEntity.class)
                        .setParameter("base", TransactionEntity.CurrencyDb.valueOf(entry.baseCurrency().name()))
                        .setParameter("quote", TransactionEntity.CurrencyDb.valueOf(entry.quoteCurrency().name()))
                        .setParameter("date", entry.rateDate())
                        .getSingleResultOrNull()
                        .flatMap(existing -> {
                            OffsetDateTime now = OffsetDateTime.now();
                            FxRateHistoryEntity entity = existing != null ? existing : new FxRateHistoryEntity();
                            if (entity.id == null) {
                                entity.id = UUID.randomUUID();
                                entity.createdAt = now;
                            }
                            entity.baseCurrency = TransactionEntity.CurrencyDb.valueOf(entry.baseCurrency().name());
                            entity.quoteCurrency = TransactionEntity.CurrencyDb.valueOf(entry.quoteCurrency().name());
                            entity.rateDate = entry.rateDate();
                            entity.rate = entry.rate();
                            entity.provider = "twelvedata";
                            entity.fetchedAt = entry.fetchedAt() != null ? entry.fetchedAt() : now;
                            entity.updatedAt = now;
                            return session.merge(entity).replaceWithVoid();
                        }));
            }
            return chain;
        });
    }

    @Override
    @WithSession
    public Uni<Optional<SpotQuote>> findSpotQuote(SpotQuoteKind kind, String symbol) {
        return Panache.getSession().flatMap(session ->
                session.createQuery(
                                "from SpotQuoteEntity s where s.kind = :kind and s.symbol = :symbol",
                                SpotQuoteEntity.class)
                        .setParameter("kind", SpotQuoteEntity.SpotQuoteKindDb.valueOf(kind.name()))
                        .setParameter("symbol", symbol)
                        .getSingleResultOrNull())
                .map(entity -> Optional.ofNullable(entity).map(this::toSpotQuote));
    }

    @Override
    @WithTransaction
    public Uni<SpotQuote> upsertSpotQuote(SpotQuote quote) {
        return Panache.getSession().flatMap(session ->
                session.createQuery(
                                "from SpotQuoteEntity s where s.kind = :kind and s.symbol = :symbol",
                                SpotQuoteEntity.class)
                        .setParameter("kind", SpotQuoteEntity.SpotQuoteKindDb.valueOf(quote.kind().name()))
                        .setParameter("symbol", quote.symbol())
                        .getSingleResultOrNull()
                        .flatMap(existing -> {
                            OffsetDateTime now = OffsetDateTime.now();
                            SpotQuoteEntity entity = existing != null ? existing : new SpotQuoteEntity();
                            if (entity.id == null) {
                                entity.id = UUID.randomUUID();
                                entity.createdAt = now;
                            }
                            entity.kind = SpotQuoteEntity.SpotQuoteKindDb.valueOf(quote.kind().name());
                            entity.symbol = quote.symbol();
                            entity.value = quote.value();
                            entity.currency = quote.currency() == null
                                    ? null
                                    : TransactionEntity.CurrencyDb.valueOf(quote.currency().name());
                            entity.baseCurrency = quote.baseCurrency() == null
                                    ? null
                                    : TransactionEntity.CurrencyDb.valueOf(quote.baseCurrency().name());
                            entity.quoteCurrency = quote.quoteCurrency() == null
                                    ? null
                                    : TransactionEntity.CurrencyDb.valueOf(quote.quoteCurrency().name());
                            entity.asOf = quote.asOf();
                            entity.source = SpotQuoteEntity.QuoteSourceDb.valueOf(quote.source().name());
                            entity.updatedAt = now;
                            return session.merge(entity).map(this::toSpotQuote);
                        }));
    }

    @Override
    @WithSession
    public Uni<Boolean> hasCoverage(String coverageKind, String symbol, LocalDate from, LocalDate to) {
        return Panache.getSession().flatMap(session ->
                session.createQuery(
                                "select count(c) from MarketDataCoverageEntity c where c.coverageKind = :kind "
                                        + "and c.symbol = :symbol and c.fromDate <= :from and c.toDate >= :to",
                                Long.class)
                        .setParameter("kind", coverageKind)
                        .setParameter("symbol", symbol)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getSingleResult())
                .map(count -> count != null && count > 0);
    }

    @Override
    @WithTransaction
    public Uni<Void> recordCoverage(String coverageKind, String symbol, LocalDate from, LocalDate to, String provider) {
        return Panache.getSession().flatMap(session -> {
            MarketDataCoverageEntity entity = new MarketDataCoverageEntity();
            entity.id = UUID.randomUUID();
            entity.coverageKind = coverageKind;
            entity.symbol = symbol;
            entity.fromDate = from;
            entity.toDate = to;
            entity.provider = provider;
            entity.fetchedAt = OffsetDateTime.now();
            entity.createdAt = OffsetDateTime.now();
            return session.merge(entity).replaceWithVoid();
        });
    }

    private PriceHistoryEntry toPriceEntry(PriceHistoryEntity entity) {
        return new PriceHistoryEntry(
                entity.symbol,
                entity.priceDate,
                entity.closePrice,
                entity.adjustedClosePrice,
                Currency.valueOf(entity.currency.name()),
                entity.fetchedAt);
    }

    private FxRateEntry toFxEntry(FxRateHistoryEntity entity) {
        return new FxRateEntry(
                Currency.valueOf(entity.baseCurrency.name()),
                Currency.valueOf(entity.quoteCurrency.name()),
                entity.rateDate,
                entity.rate,
                entity.fetchedAt);
    }

    private SpotQuote toSpotQuote(SpotQuoteEntity entity) {
        return new SpotQuote(
                SpotQuoteKind.valueOf(entity.kind.name()),
                entity.symbol,
                entity.value,
                entity.currency == null ? null : Currency.valueOf(entity.currency.name()),
                entity.baseCurrency == null ? null : Currency.valueOf(entity.baseCurrency.name()),
                entity.quoteCurrency == null ? null : Currency.valueOf(entity.quoteCurrency.name()),
                entity.asOf,
                QuoteSource.valueOf(entity.source.name()));
    }
}

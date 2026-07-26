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
    public Uni<Void> upsertPrices(List<PriceHistoryEntry> entries, String provider) {
        if (entries == null || entries.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        requireProvider(provider);
        OffsetDateTime now = OffsetDateTime.now();
        StringBuilder sql = new StringBuilder(
                "insert into price_history "
                        + "(symbol, price_date, close_price, adjusted_close_price, currency, provider, "
                        + "fetched_at, updated_at) values ");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(:symbol").append(i).append(", :date").append(i).append(", :close").append(i)
                    .append(", :adjClose").append(i).append(", (:currency").append(i).append(")::currency_type")
                    .append(", :provider").append(i).append(", :fetchedAt").append(i)
                    .append(", :updatedAt").append(i).append(")");
        }
        sql.append(" on conflict (symbol, price_date) do update set "
                + "close_price = excluded.close_price, "
                + "adjusted_close_price = excluded.adjusted_close_price, "
                + "currency = excluded.currency, "
                + "provider = excluded.provider, "
                + "fetched_at = excluded.fetched_at, "
                + "updated_at = excluded.updated_at");

        return Panache.getSession().flatMap(session -> {
            var query = session.createNativeQuery(sql.toString());
            for (int i = 0; i < entries.size(); i++) {
                PriceHistoryEntry entry = entries.get(i);
                query.setParameter("symbol" + i, entry.symbol());
                query.setParameter("date" + i, entry.priceDate());
                query.setParameter("close" + i, entry.closePrice());
                query.setParameter("adjClose" + i, entry.adjustedClosePrice());
                query.setParameter("currency" + i, entry.currency().name());
                query.setParameter("provider" + i, provider);
                query.setParameter("fetchedAt" + i, entry.fetchedAt() != null ? entry.fetchedAt() : now);
                query.setParameter("updatedAt" + i, now);
            }
            return query.executeUpdate();
        }).replaceWithVoid();
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
    public Uni<Void> upsertFxRates(List<FxRateEntry> entries, String provider) {
        if (entries == null || entries.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        requireProvider(provider);
        OffsetDateTime now = OffsetDateTime.now();
        StringBuilder sql = new StringBuilder(
                "insert into fx_rate_history "
                        + "(base_currency, quote_currency, rate_date, rate, provider, fetched_at, updated_at) "
                        + "values ");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("((:base").append(i).append(")::currency_type, (:quote").append(i).append(")::currency_type")
                    .append(", :date").append(i).append(", :rate").append(i).append(", :provider").append(i)
                    .append(", :fetchedAt").append(i).append(", :updatedAt").append(i).append(")");
        }
        sql.append(" on conflict (base_currency, quote_currency, rate_date) do update set "
                + "rate = excluded.rate, "
                + "provider = excluded.provider, "
                + "fetched_at = excluded.fetched_at, "
                + "updated_at = excluded.updated_at");

        return Panache.getSession().flatMap(session -> {
            var query = session.createNativeQuery(sql.toString());
            for (int i = 0; i < entries.size(); i++) {
                FxRateEntry entry = entries.get(i);
                query.setParameter("base" + i, entry.baseCurrency().name());
                query.setParameter("quote" + i, entry.quoteCurrency().name());
                query.setParameter("date" + i, entry.rateDate());
                query.setParameter("rate" + i, entry.rate());
                query.setParameter("provider" + i, provider);
                query.setParameter("fetchedAt" + i, entry.fetchedAt() != null ? entry.fetchedAt() : now);
                query.setParameter("updatedAt" + i, now);
            }
            return query.executeUpdate();
        }).replaceWithVoid();
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
    public Uni<SpotQuote> upsertSpotQuote(SpotQuote quote, String provider) {
        if (quote.source() == QuoteSource.PROVIDER) {
            requireProvider(provider);
        }
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
                            entity.provider = quote.source() == QuoteSource.MANUAL ? null : provider;
                            entity.updatedAt = now;
                            return session.merge(entity).map(this::toSpotQuote);
                        }));
    }

    @Override
    @WithTransaction
    public Uni<SpotQuote> upsertManualSpotQuote(SpotQuote quote) {
        if (quote.source() != QuoteSource.MANUAL) {
            return Uni.createFrom().failure(
                    new IllegalArgumentException("upsertManualSpotQuote requires source=MANUAL"));
        }
        return upsertSpotQuote(quote, null);
    }

    @Override
    @WithTransaction
    public Uni<Void> deleteSpotQuote(SpotQuoteKind kind, String symbol) {
        return Panache.getSession().flatMap(session ->
                session.createQuery("delete from SpotQuoteEntity s where s.kind = :kind and s.symbol = :symbol")
                        .setParameter("kind", SpotQuoteEntity.SpotQuoteKindDb.valueOf(kind.name()))
                        .setParameter("symbol", symbol)
                        .executeUpdate())
                .replaceWithVoid();
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
        OffsetDateTime now = OffsetDateTime.now();
        return Panache.getSession().flatMap(session -> session.createNativeQuery(
                                "insert into market_data_coverage "
                                        + "(id, coverage_kind, symbol, from_date, to_date, provider, fetched_at, created_at) "
                                        + "values (:id, :kind, :symbol, :from, :to, :provider, :fetchedAt, :createdAt) "
                                        + "on conflict (coverage_kind, symbol, from_date, to_date) do update set "
                                        + "provider = excluded.provider, fetched_at = excluded.fetched_at")
                        .setParameter("id", UUID.randomUUID())
                        .setParameter("kind", coverageKind)
                        .setParameter("symbol", symbol)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .setParameter("provider", provider)
                        .setParameter("fetchedAt", now)
                        .setParameter("createdAt", now)
                        .executeUpdate())
                .replaceWithVoid();
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

    private void requireProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank for provider market data");
        }
    }
}

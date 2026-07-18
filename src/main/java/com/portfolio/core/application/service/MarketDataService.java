package com.portfolio.core.application.service;

import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.QuoteSource;
import com.portfolio.core.model.SpotQuote;
import com.portfolio.core.model.SpotQuoteKind;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.MarketDataProviderPort;
import com.portfolio.core.ports.outgoing.MarketDataStorePort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Store-first read-through market data: read Postgres, fetch only missing gaps, write back.
 */
@ApplicationScoped
public class MarketDataService implements MarketDataPort {

    private static final Logger LOG = Logger.getLogger(MarketDataService.class);

    private final MarketDataStorePort store;
    private final MarketDataProviderPort provider;
    private final Duration spotPriceFreshness;
    private final Duration fxFreshness;

    public MarketDataService(
            MarketDataStorePort store,
            MarketDataProviderPort provider,
            @ConfigProperty(name = "application.market-data.spot-price-freshness", defaultValue = "PT15M")
            Duration spotPriceFreshness,
            @ConfigProperty(name = "application.market-data.fx-freshness", defaultValue = "PT1H")
            Duration fxFreshness) {
        this.store = store;
        this.provider = provider;
        this.spotPriceFreshness = spotPriceFreshness;
        this.fxFreshness = fxFreshness;
    }

    @Override
    public Uni<BigDecimal> getSpotPrice(String symbol) {
        String normalized = symbol.trim().toUpperCase();
        return store.findSpotQuote(SpotQuoteKind.PRICE, normalized)
                .flatMap(optional -> {
                    if (optional.isPresent() && isFresh(optional.get().asOf(), spotPriceFreshness)) {
                        LOG.infof("Using cached spot price symbol=%s", normalized);
                        return Uni.createFrom().item(optional.get().value());
                    }
                    LOG.infof("Fetching spot price from provider symbol=%s", normalized);
                    return provider.fetchSpotPrice(normalized)
                            .flatMap(price -> {
                                SpotQuote quote = new SpotQuote(
                                        SpotQuoteKind.PRICE,
                                        normalized,
                                        price,
                                        null,
                                        null,
                                        null,
                                        OffsetDateTime.now(),
                                        QuoteSource.PROVIDER);
                                return store.upsertSpotQuote(quote).map(SpotQuote::value);
                            });
                });
    }

    @Override
    public Uni<BigDecimal> getFxRate(Currency base, Currency quote) {
        if (base == quote) {
            return Uni.createFrom().item(BigDecimal.ONE);
        }
        String symbol = fxSymbol(base, quote);
        return store.findSpotQuote(SpotQuoteKind.FX, symbol)
                .flatMap(optional -> {
                    if (optional.isPresent() && isFresh(optional.get().asOf(), fxFreshness)) {
                        LOG.infof("Using cached FX rate symbol=%s", symbol);
                        return Uni.createFrom().item(optional.get().value());
                    }
                    LOG.infof("Fetching FX rate from provider symbol=%s", symbol);
                    return provider.fetchFxRate(base, quote)
                            .flatMap(rate -> {
                                SpotQuote spot = new SpotQuote(
                                        SpotQuoteKind.FX,
                                        symbol,
                                        rate,
                                        null,
                                        base,
                                        quote,
                                        OffsetDateTime.now(),
                                        QuoteSource.PROVIDER);
                                return store.upsertSpotQuote(spot).map(SpotQuote::value);
                            });
                });
    }

    @Override
    public Uni<List<PriceHistoryEntry>> getPriceHistory(String symbol, LocalDate from, LocalDate to) {
        String normalized = symbol.trim().toUpperCase();
        return store.findPrices(normalized, from, to)
                .flatMap(stored -> {
                    Set<LocalDate> present = stored.stream()
                            .map(PriceHistoryEntry::priceDate)
                            .collect(Collectors.toCollection(HashSet::new));
                    List<LocalDate> missing = datesBetween(from, to).stream()
                            .filter(date -> !present.contains(date))
                            .toList();
                    if (missing.isEmpty()) {
                        LOG.infof("Price history fully cached symbol=%s from=%s to=%s", normalized, from, to);
                        return Uni.createFrom().item(stored);
                    }
                    return store.hasCoverage("PRICE", normalized, from, to)
                            .flatMap(covered -> {
                                if (Boolean.TRUE.equals(covered)) {
                                    LOG.infof(
                                            "Price history covered without provider fetch symbol=%s from=%s to=%s",
                                            normalized, from, to);
                                    return Uni.createFrom().item(stored);
                                }
                                LocalDate fetchFrom = missing.getFirst();
                                LocalDate fetchTo = missing.getLast();
                                LOG.infof(
                                        "Fetching price history from provider symbol=%s from=%s to=%s",
                                        normalized, fetchFrom, fetchTo);
                                return provider.fetchPriceHistory(normalized, fetchFrom, fetchTo)
                                        .flatMap(fetched -> store.upsertPrices(fetched)
                                                .chain(() -> store.recordCoverage(
                                                        "PRICE", normalized, from, to, "twelvedata"))
                                                .chain(() -> store.findPrices(normalized, from, to)));
                            });
                });
    }

    @Override
    public Uni<List<FxRateEntry>> getFxHistory(Currency base, Currency quote, LocalDate from, LocalDate to) {
        if (base == quote) {
            List<FxRateEntry> identity = datesBetween(from, to).stream()
                    .map(date -> new FxRateEntry(base, quote, date, BigDecimal.ONE, OffsetDateTime.now()))
                    .toList();
            return Uni.createFrom().item(identity);
        }
        String symbol = fxSymbol(base, quote);
        return store.findFxRates(base, quote, from, to)
                .flatMap(stored -> {
                    Set<LocalDate> present = stored.stream()
                            .map(FxRateEntry::rateDate)
                            .collect(Collectors.toCollection(HashSet::new));
                    List<LocalDate> missing = datesBetween(from, to).stream()
                            .filter(date -> !present.contains(date))
                            .toList();
                    if (missing.isEmpty()) {
                        LOG.infof("FX history fully cached symbol=%s from=%s to=%s", symbol, from, to);
                        return Uni.createFrom().item(stored);
                    }
                    return store.hasCoverage("FX", symbol, from, to)
                            .flatMap(covered -> {
                                if (Boolean.TRUE.equals(covered)) {
                                    LOG.infof(
                                            "FX history covered without provider fetch symbol=%s from=%s to=%s",
                                            symbol, from, to);
                                    return Uni.createFrom().item(stored);
                                }
                                LOG.infof(
                                        "Fetching FX history from provider symbol=%s from=%s to=%s",
                                        symbol, missing.getFirst(), missing.getLast());
                                return provider.fetchFxHistory(base, quote, missing.getFirst(), missing.getLast())
                                        .flatMap(fetched -> store.upsertFxRates(fetched)
                                                .chain(() -> store.recordCoverage(
                                                        "FX", symbol, from, to, "twelvedata"))
                                                .chain(() -> store.findFxRates(base, quote, from, to)));
                            });
                });
    }

    @Override
    public Uni<SpotQuote> setManualPrice(String symbol, BigDecimal price, Currency currency) {
        String normalized = symbol.trim().toUpperCase();
        LOG.infof("Setting manual price symbol=%s price=%s currency=%s", normalized, price, currency);
        SpotQuote quote = new SpotQuote(
                SpotQuoteKind.PRICE,
                normalized,
                price,
                currency,
                null,
                null,
                OffsetDateTime.now(),
                QuoteSource.MANUAL);
        return store.upsertSpotQuote(quote);
    }

    private static boolean isFresh(OffsetDateTime asOf, Duration freshness) {
        return asOf != null && asOf.isAfter(OffsetDateTime.now().minus(freshness));
    }

    private static String fxSymbol(Currency base, Currency quote) {
        return base.name() + "/" + quote.name();
    }

    private static List<LocalDate> datesBetween(LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            dates.add(date);
        }
        return dates.stream().sorted(Comparator.naturalOrder()).toList();
    }
}

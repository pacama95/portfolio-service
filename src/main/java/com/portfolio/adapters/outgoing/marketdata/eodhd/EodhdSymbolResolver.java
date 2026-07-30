package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.portfolio.adapters.common.ReactiveRateLimiter;
import com.portfolio.adapters.outgoing.marketdata.adapter.MarketDataRateLimiters;
import com.portfolio.core.application.service.CurrencyResolver;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.InstrumentListing;
import com.portfolio.core.ports.outgoing.InstrumentListingPort;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Translates a canonical ticker into EODHD's {@code TICKER.EXCHANGE} form.
 *
 * <p>A persisted mapping is authoritative and short-circuits everything else: writing a
 * transaction for the ticker deletes the mapping, so nothing here has to detect staleness.
 * Discovery runs only on a miss.
 */
@ApplicationScoped
class EodhdSymbolResolver {

    private static final Logger LOG = Logger.getLogger(EodhdSymbolResolver.class);
    private static final Map<String, String> COUNTRY_CODES = countryCodes();

    private final EodhdClient client;
    private final EodhdExchangeCatalog catalog;
    private final EodhdReferenceDataStore store;
    private final InstrumentListingPort listings;
    private final CurrencyResolver currencyResolver;
    private final String apiKey;
    private final ReactiveRateLimiter rateLimiter;

    @Inject
    EodhdSymbolResolver(
            @RestClient EodhdClient client,
            EodhdExchangeCatalog catalog,
            EodhdReferenceDataStore store,
            InstrumentListingPort listings,
            CurrencyResolver currencyResolver,
            @ConfigProperty(name = "application.market-data.eodhd.api-key") String apiKey,
            @Named(MarketDataRateLimiters.EODHD) ReactiveRateLimiter rateLimiter) {
        this.client = client;
        this.catalog = catalog;
        this.store = store;
        this.listings = listings;
        this.currencyResolver = currencyResolver;
        this.apiKey = apiKey;
        this.rateLimiter = rateLimiter;
    }

    Uni<EodhdResolvedSymbol> resolve(String symbol) {
        String canonical = normalize(symbol);
        if (canonical.isBlank()) {
            return Uni.createFrom().failure(
                    new MarketDataProviderError.SymbolResolution(canonical, "canonical symbol is blank"));
        }
        return store.findSymbolMapping(canonical).flatMap(cached -> {
            if (cached.isPresent()) {
                LOG.debugf("Reusing EODHD symbol mapping symbol=%s source=%s",
                        canonical, cached.get().resolutionSource());
                return Uni.createFrom().item(toResolved(cached.get()));
            }
            return discover(canonical);
        });
    }

    private Uni<EodhdResolvedSymbol> discover(String canonical) {
        // Both adapters use Hibernate Reactive and may join the same request-scoped session.
        // Reactive sessions do not support concurrent queries, so load these inputs in sequence.
        return catalog.exchanges().flatMap(exchanges -> listings.findBySymbol(canonical)
                .flatMap(instrumentListings -> {
                    ListingHints hints = listingHints(canonical, instrumentListings);
                    Map<String, ExchangeAliases> active = activeExchanges(exchanges);
                    String exchangeFilter = exchangeFilter(hints, active.values());
                    return rateLimiter.acquire()
                            .onFailure(ReactiveRateLimiter.RateLimitExceededException.class)
                            .transform(failure -> new MarketDataProviderError.RateLimited(
                                    canonical, failure.retryAfter()))
                            .chain(() -> client.search(canonical, apiKey, "json", exchangeFilter))
                            .onFailure().transform(failure -> EodhdFailureMapper.translate(failure, canonical))
                            .map(results -> choose(canonical, results, hints, active))
                            .flatMap(mapping -> persist(canonical, mapping));
                }));
    }

    private EodhdSymbolMapping choose(
            String canonical,
            List<EodhdSearchResponse> results,
            ListingHints hints,
            Map<String, ExchangeAliases> activeExchanges) {
        if (results == null) {
            throw new MarketDataProviderError.MissingData(canonical, "search results");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (EodhdSearchResponse result : results) {
            if (result.code() == null || !canonical.equalsIgnoreCase(result.code().trim())) {
                continue;
            }
            ExchangeAliases exchange = activeExchanges.get(normalize(result.exchange()));
            if (exchange == null || !matchesCountry(hints, result, exchange)
                    || !matchesCurrency(hints, result, exchange)) {
                continue;
            }
            candidates.add(new Candidate(result, exchange));
        }

        // Broker exchange labels ("NASDAQ", "BOLSA DE MADRID") often match neither EODHD's
        // aggregate exchange code nor its MICs, so the exchange hint only narrows an otherwise
        // ambiguous result; it never discards the last candidate country and currency accepted.
        List<Candidate> narrowed = candidates.stream()
                .filter(candidate -> hints.exchange() != null && candidate.exchange().matches(hints.exchange()))
                .toList();
        Map<String, Candidate> selected = new LinkedHashMap<>();
        (narrowed.isEmpty() ? candidates : narrowed)
                .forEach(candidate -> selected.putIfAbsent(candidate.exchange().code(), candidate));

        if (selected.size() != 1) {
            throw new MarketDataProviderError.SymbolResolution(
                    canonical,
                    selected.isEmpty() ? "no exact active search result" : "ambiguous exact search results");
        }
        Candidate chosen = selected.values().iterator().next();
        return new EodhdSymbolMapping(
                canonical,
                canonical + "." + chosen.exchange().code(),
                chosen.exchange().code(),
                firstNonBlank(chosen.response().currency(), chosen.exchange().currency()),
                hints.hasHints() ? EodhdResolutionSource.TRANSACTION_METADATA
                        : EodhdResolutionSource.UNIQUE_SEARCH,
                OffsetDateTime.now());
    }

    private boolean matchesCountry(ListingHints hints, EodhdSearchResponse result, ExchangeAliases exchange) {
        if (hints.country() == null) {
            return true;
        }
        String expected = normalizeCountry(hints.country());
        return expected.equals(normalizeCountry(result.country())) || exchange.countries().contains(expected);
    }

    private boolean matchesCurrency(ListingHints hints, EodhdSearchResponse result, ExchangeAliases exchange) {
        if (hints.currency() == null) {
            return true;
        }
        String rawCurrency = firstNonBlank(result.currency(), exchange.currency());
        return rawCurrency != null
                && currencyResolver.resolve(rawCurrency)
                .map(resolution -> resolution.currency() == hints.currency())
                .orElse(false);
    }

    private ListingHints listingHints(String canonical, List<InstrumentListing> instrumentListings) {
        Set<String> exchanges = distinct(instrumentListings, InstrumentListing::exchange, EodhdSymbolResolver::normalize);
        Set<String> countries = distinct(instrumentListings, InstrumentListing::country,
                EodhdSymbolResolver::normalizeCountry);
        Set<Currency> currencies = new TreeSet<>();
        instrumentListings.stream().map(InstrumentListing::currency).filter(Objects::nonNull)
                .forEach(currencies::add);
        if (exchanges.size() > 1 || countries.size() > 1 || currencies.size() > 1) {
            throw new MarketDataProviderError.SymbolResolution(canonical, "conflicting transaction listing metadata");
        }
        return new ListingHints(
                first(exchanges),
                first(countries),
                currencies.isEmpty() ? null : currencies.iterator().next());
    }

    /** Every label EODHD or a broker might use for one exchange, so hint matching is one lookup. */
    private Map<String, ExchangeAliases> activeExchanges(List<EodhdExchange> exchanges) {
        Map<String, ExchangeAliases> active = new LinkedHashMap<>();
        for (EodhdExchange exchange : exchanges) {
            if (!exchange.active()) {
                continue;
            }
            String code = normalize(exchange.code());
            Set<String> aliases = new TreeSet<>();
            aliases.add(code);
            addIfPresent(aliases, normalize(exchange.name()));
            if (exchange.operatingMic() != null) {
                for (String mic : exchange.operatingMic().split(",")) {
                    addIfPresent(aliases, normalize(mic));
                }
            }
            Set<String> countries = new TreeSet<>();
            addIfPresent(countries, normalizeCountry(exchange.country()));
            addIfPresent(countries, normalizeCountry(exchange.countryIso2()));
            addIfPresent(countries, normalizeCountry(exchange.countryIso3()));
            active.put(code, new ExchangeAliases(code, exchange.currency(), aliases, countries));
        }
        return active;
    }

    /** EODHD supports an exchange filter, but only when the hint maps to exactly one exchange. */
    private String exchangeFilter(ListingHints hints, Iterable<ExchangeAliases> exchanges) {
        if (hints.exchange() == null) {
            return null;
        }
        String match = null;
        for (ExchangeAliases exchange : exchanges) {
            if (exchange.matches(hints.exchange())) {
                if (match != null) {
                    return null;
                }
                match = exchange.code();
            }
        }
        return match;
    }

    private Uni<EodhdResolvedSymbol> persist(String canonical, EodhdSymbolMapping mapping) {
        return store.upsertSymbolMapping(mapping)
                .replaceWith(toResolved(mapping))
                .invoke(resolved -> LOG.infof(
                        "Resolved EODHD symbol=%s source=%s", canonical, mapping.resolutionSource()));
    }

    private EodhdResolvedSymbol toResolved(EodhdSymbolMapping mapping) {
        return new EodhdResolvedSymbol(
                mapping.canonicalSymbol(),
                mapping.providerSymbol(),
                mapping.exchangeCode(),
                mapping.rawCurrency());
    }

    private static Set<String> distinct(
            List<InstrumentListing> listings,
            Function<InstrumentListing, String> field,
            UnaryOperator<String> normalizer) {
        Set<String> distinct = new TreeSet<>();
        for (InstrumentListing listing : listings) {
            String value = field.apply(listing);
            if (value != null && !value.isBlank()) {
                distinct.add(normalizer.apply(value));
            }
        }
        return distinct;
    }

    private static String first(Set<String> values) {
        return values.isEmpty() ? null : values.iterator().next();
    }

    private static void addIfPresent(Set<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    /**
     * Converts ISO alpha-2, ISO alpha-3, and English display names to one country identifier.
     * EODHD commonly returns {@code USA}, while transaction suggestions use
     * {@code United States}; this translation is deliberately private to the adapter.
     */
    private static String normalizeCountry(String value) {
        String key = countryKey(value);
        return COUNTRY_CODES.getOrDefault(key, key);
    }

    private static Map<String, String> countryCodes() {
        Map<String, String> aliases = new HashMap<>();
        for (String alpha2 : Locale.getISOCountries()) {
            Locale country = Locale.of("", alpha2);
            aliases.put(countryKey(alpha2), alpha2);
            aliases.put(countryKey(country.getISO3Country()), alpha2);
            aliases.put(countryKey(country.getDisplayCountry(Locale.ENGLISH)), alpha2);
        }
        // EODHD uses UK in some exchange rows, although the ISO alpha-2 code is GB.
        aliases.put("UK", "GB");
        return Map.copyOf(aliases);
    }

    private static String countryKey(String value) {
        return normalize(value).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private record ListingHints(String exchange, String country, Currency currency) {

        boolean hasHints() {
            return exchange != null || country != null || currency != null;
        }
    }

    private record ExchangeAliases(String code, String currency, Set<String> aliases, Set<String> countries) {

        boolean matches(String label) {
            return aliases.contains(normalize(label));
        }
    }

    private record Candidate(EodhdSearchResponse response, ExchangeAliases exchange) {
    }
}

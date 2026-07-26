package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.portfolio.adapters.common.ReactiveRateLimiter;
import com.portfolio.adapters.outgoing.marketdata.adapter.MarketDataRateLimiters;
import com.portfolio.core.application.service.CurrencyResolver;
import com.portfolio.core.model.InstrumentListing;
import com.portfolio.core.ports.outgoing.InstrumentListingPort;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@ApplicationScoped
class EodhdSymbolResolver {

    private static final Logger LOG = Logger.getLogger(EodhdSymbolResolver.class);
    private static final String OVERRIDE_PREFIX = "application.market-data.eodhd.symbol-overrides.";

    private final EodhdClient client;
    private final EodhdExchangeCatalog catalog;
    private final EodhdReferenceDataStore store;
    private final InstrumentListingPort listings;
    private final CurrencyResolver currencyResolver;
    private final String apiKey;
    private final ReactiveRateLimiter rateLimiter;
    private final Map<String, String> overrides;

    @Inject
    EodhdSymbolResolver(
            @RestClient EodhdClient client,
            EodhdExchangeCatalog catalog,
            EodhdReferenceDataStore store,
            InstrumentListingPort listings,
            CurrencyResolver currencyResolver,
            @ConfigProperty(name = "application.market-data.eodhd.api-key") String apiKey,
            @Named(MarketDataRateLimiters.EODHD) ReactiveRateLimiter rateLimiter,
            Config config) {
        this(client, catalog, store, listings, currencyResolver, apiKey, rateLimiter, extractOverrides(config));
    }

    EodhdSymbolResolver(
            EodhdClient client,
            EodhdExchangeCatalog catalog,
            EodhdReferenceDataStore store,
            InstrumentListingPort listings,
            CurrencyResolver currencyResolver,
            String apiKey,
            ReactiveRateLimiter rateLimiter,
            Map<String, String> overrides) {
        this.client = client;
        this.catalog = catalog;
        this.store = store;
        this.listings = listings;
        this.currencyResolver = currencyResolver;
        this.apiKey = apiKey;
        this.rateLimiter = rateLimiter;
        this.overrides = normalizeOverrides(overrides);
    }

    Uni<EodhdResolvedSymbol> resolve(String symbol) {
        String canonical = normalize(symbol);
        if (canonical.isBlank()) {
            return Uni.createFrom().failure(
                    new MarketDataProviderError.SymbolResolution(canonical, "canonical symbol is blank"));
        }
        return Uni.combine().all().unis(catalog.exchanges(), listings.findBySymbol(canonical)).asTuple()
                .flatMap(context -> resolve(canonical, context.getItem1(), context.getItem2()));
    }

    private Uni<EodhdResolvedSymbol> resolve(
            String canonical,
            List<EodhdExchange> exchanges,
            List<InstrumentListing> instrumentListings) {
        String fingerprint = fingerprint(instrumentListings);
        String override = overrides.get(canonical);
        if (override != null) {
            return persist(canonical, overrideMapping(canonical, override, fingerprint, exchanges));
        }
        return store.findSymbolMapping(canonical).flatMap(cached -> {
            if (cached.filter(mapping -> mappingIsReusable(mapping, fingerprint, exchanges)).isPresent()) {
                EodhdSymbolMapping mapping = cached.orElseThrow();
                LOG.debugf("Reusing EODHD symbol mapping symbol=%s source=%s",
                        canonical, mapping.resolutionSource());
                return Uni.createFrom().item(toResolved(mapping));
            }
            ListingHints hints = listingHints(canonical, instrumentListings);
            String exchangeFilter = exchangeFilter(hints, exchanges).orElse(null);
            return rateLimiter.acquire()
                    .onFailure(ReactiveRateLimiter.RateLimitExceededException.class)
                    .transform(failure -> new MarketDataProviderError.RateLimited(
                            canonical,
                            ((ReactiveRateLimiter.RateLimitExceededException) failure).retryAfter()))
                    .chain(() -> client.search(canonical, apiKey, "json", exchangeFilter))
                    .onFailure().transform(failure -> EodhdFailureMapper.translate(failure, canonical))
                    .map(results -> choose(canonical, results, hints, exchanges, fingerprint))
                    .flatMap(mapping -> persist(canonical, mapping));
        });
    }

    private EodhdSymbolMapping choose(
            String canonical,
            List<EodhdSearchResponse> results,
            ListingHints hints,
            List<EodhdExchange> exchanges,
            String fingerprint) {
        if (results == null) {
            throw new MarketDataProviderError.MissingData(canonical, "search results");
        }
        Map<String, EodhdExchange> activeExchanges = new HashMap<>();
        for (EodhdExchange exchange : exchanges) {
            if (exchange.active()) {
                activeExchanges.put(normalize(exchange.code()), exchange);
            }
        }
        List<SearchCandidate> candidates = new ArrayList<>();
        for (EodhdSearchResponse result : results) {
            if (result.code() == null || !canonical.equalsIgnoreCase(result.code().trim())) {
                continue;
            }
            if (result.exchange() == null || result.exchange().isBlank()) {
                continue;
            }
            EodhdExchange exchange = activeExchanges.get(normalize(result.exchange()));
            if (exchange == null || !matchesHints(result, exchange, hints)) {
                continue;
            }
            candidates.add(new SearchCandidate(result, exchange));
        }

        Map<String, SearchCandidate> uniqueBySymbol = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates) {
            uniqueBySymbol.putIfAbsent(
                    canonical + "." + normalize(candidate.exchange().code()),
                    candidate);
        }
        if (uniqueBySymbol.size() != 1) {
            throw new MarketDataProviderError.SymbolResolution(
                    canonical,
                    uniqueBySymbol.isEmpty() ? "no exact active search result" : "ambiguous exact search results");
        }
        SearchCandidate selected = uniqueBySymbol.values().iterator().next();
        String exchangeCode = normalize(selected.exchange().code());
        String rawCurrency = firstNonBlank(selected.response().currency(), selected.exchange().currency());
        return new EodhdSymbolMapping(
                canonical,
                canonical + "." + exchangeCode,
                exchangeCode,
                rawCurrency,
                fingerprint,
                hints.hasMetadata()
                        ? EodhdResolutionSource.TRANSACTION_METADATA
                        : EodhdResolutionSource.UNIQUE_SEARCH,
                OffsetDateTime.now());
    }

    private boolean matchesHints(EodhdSearchResponse result, EodhdExchange exchange, ListingHints hints) {
        if (!hints.hasMetadata()) {
            return true;
        }
        boolean exchangeMatch = hints.exchange() == null || matchesExchange(hints.exchange(), result, exchange);
        boolean countryMatch = hints.country() == null || matchesCountry(hints.country(), result, exchange);
        boolean currencyMatch = hints.currency() == null || matchesCurrency(hints, result, exchange);
        // Broker exchange labels such as NASDAQ do not necessarily match EODHD's aggregate `US`
        // exchange code or its ISO MICs. Country + currency may therefore establish the same
        // listing, but only uniqueness can ultimately select it.
        return currencyMatch && countryMatch && (exchangeMatch || (hints.country() != null && hints.currency() != null));
    }

    private boolean matchesExchange(String listingExchange, EodhdSearchResponse result, EodhdExchange exchange) {
        String expected = normalize(listingExchange);
        if (expected.equals(normalize(result.exchange()))
                || expected.equals(normalize(exchange.code()))
                || expected.equals(normalize(exchange.name()))) {
            return true;
        }
        if (exchange.operatingMic() == null) {
            return false;
        }
        for (String mic : exchange.operatingMic().split(",")) {
            if (expected.equals(normalize(mic))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCountry(String listingCountry, EodhdSearchResponse result, EodhdExchange exchange) {
        String expected = normalize(listingCountry);
        return expected.equals(normalize(result.country()))
                || expected.equals(normalize(exchange.country()))
                || expected.equals(normalize(exchange.countryIso2()))
                || expected.equals(normalize(exchange.countryIso3()));
    }

    private boolean matchesCurrency(ListingHints hints, EodhdSearchResponse result, EodhdExchange exchange) {
        String rawCurrency = firstNonBlank(result.currency(), exchange.currency());
        return rawCurrency != null
                && currencyResolver.resolve(rawCurrency)
                .map(resolution -> resolution.currency() == hints.currency())
                .orElse(false);
    }

    private ListingHints listingHints(String canonical, List<InstrumentListing> instrumentListings) {
        Set<String> exchanges = distinctNonBlank(instrumentListings.stream().map(InstrumentListing::exchange).toList());
        Set<String> countries = distinctNonBlank(instrumentListings.stream().map(InstrumentListing::country).toList());
        Set<com.portfolio.core.model.Currency> currencies = new TreeSet<>();
        instrumentListings.stream().map(InstrumentListing::currency).filter(java.util.Objects::nonNull)
                .forEach(currencies::add);
        if (exchanges.size() > 1 || countries.size() > 1 || currencies.size() > 1) {
            throw new MarketDataProviderError.SymbolResolution(canonical, "conflicting transaction listing metadata");
        }
        InstrumentListing first = instrumentListings.isEmpty() ? null : instrumentListings.getFirst();
        return new ListingHints(
                exchanges.isEmpty() ? null : exchanges.iterator().next(),
                countries.isEmpty() ? null : countries.iterator().next(),
                first == null ? null : first.currency(),
                !instrumentListings.isEmpty());
    }

    private Optional<String> exchangeFilter(ListingHints hints, List<EodhdExchange> exchanges) {
        if (hints.exchange() == null) {
            return Optional.empty();
        }
        List<EodhdExchange> matches = exchanges.stream()
                .filter(EodhdExchange::active)
                .filter(exchange -> matchesExchange(
                        hints.exchange(),
                        new EodhdSearchResponse(null, exchange.code(), null, null, null, null, null, null),
                        exchange))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst().code()) : Optional.empty();
    }

    private EodhdSymbolMapping overrideMapping(
            String canonical,
            String providerSymbol,
            String fingerprint,
            List<EodhdExchange> exchanges) {
        int separator = providerSymbol.lastIndexOf('.');
        if (separator < 1 || separator == providerSymbol.length() - 1) {
            throw new MarketDataProviderError.SymbolResolution(canonical, "configured override is not qualified");
        }
        String exchangeCode = normalize(providerSymbol.substring(separator + 1));
        EodhdExchange exchange = exchanges.stream()
                .filter(candidate -> exchangeCode.equals(normalize(candidate.code())))
                .findFirst()
                .orElseThrow(() -> new MarketDataProviderError.SymbolResolution(
                        canonical, "configured override uses an unknown exchange code"));
        return new EodhdSymbolMapping(
                canonical,
                providerSymbol.trim().toUpperCase(Locale.ROOT),
                exchangeCode,
                exchange.currency(),
                fingerprint,
                EodhdResolutionSource.CONFIG_OVERRIDE,
                OffsetDateTime.now());
    }

    private boolean mappingIsReusable(
            EodhdSymbolMapping mapping,
            String fingerprint,
            List<EodhdExchange> exchanges) {
        return fingerprint.equals(mapping.metadataFingerprint())
                && exchanges.stream().anyMatch(exchange ->
                normalize(exchange.code()).equals(normalize(mapping.exchangeCode())));
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

    private String fingerprint(List<InstrumentListing> instrumentListings) {
        List<String> normalized = instrumentListings.stream()
                .map(listing -> String.join("|",
                        normalize(listing.symbol()),
                        normalize(listing.exchange()),
                        normalize(listing.country()),
                        listing.currency() == null ? "" : listing.currency().name()))
                .sorted(Comparator.naturalOrder())
                .toList();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("\n", normalized)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private Set<String> distinctNonBlank(List<String> values) {
        Set<String> distinct = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                distinct.add(normalize(value));
            }
        }
        return distinct;
    }

    private static Map<String, String> extractOverrides(Config config) {
        Map<String, String> values = new HashMap<>();
        for (String propertyName : config.getPropertyNames()) {
            if (propertyName.startsWith(OVERRIDE_PREFIX)) {
                String symbol = propertyName.substring(OVERRIDE_PREFIX.length());
                config.getOptionalValue(propertyName, String.class)
                        .ifPresent(value -> values.put(symbol, value));
            }
        }
        return values;
    }

    private static Map<String, String> normalizeOverrides(Map<String, String> overrides) {
        Map<String, String> normalized = new HashMap<>();
        overrides.forEach((symbol, providerSymbol) -> normalized.put(normalize(symbol), providerSymbol));
        return Map.copyOf(normalized);
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

    private record ListingHints(
            String exchange,
            String country,
            com.portfolio.core.model.Currency currency,
            boolean hasMetadata
    ) {
    }

    private record SearchCandidate(EodhdSearchResponse response, EodhdExchange exchange) {
    }
}

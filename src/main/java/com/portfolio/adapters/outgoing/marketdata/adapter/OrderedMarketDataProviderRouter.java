package com.portfolio.adapters.outgoing.marketdata.adapter;

import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.MarketDataProviderResult;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import com.portfolio.core.ports.outgoing.MarketDataProviderPort;
import com.portfolio.core.ports.outgoing.RoutedMarketDataProviderPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@ApplicationScoped
public class OrderedMarketDataProviderRouter implements MarketDataProviderPort, RoutedMarketDataProviderPort {

    private static final Logger LOG = Logger.getLogger(OrderedMarketDataProviderRouter.class);
    private static final String TWELVE_DATA = "twelvedata";
    private static final String EODHD = "eodhd";

    private final List<Provider> providers;

    @Inject
    public OrderedMarketDataProviderRouter(
            @MarketDataProviderAdapter(TWELVE_DATA) MarketDataProviderPort twelveData,
            @MarketDataProviderAdapter(EODHD) MarketDataProviderPort eodhd,
            @ConfigProperty(name = "application.market-data.providers", defaultValue = "twelvedata,eodhd")
            String configuredProviders,
            Config config) {
        this(twelveData, eodhd, configuredProviders);
        validateCredentials(config);
    }

    OrderedMarketDataProviderRouter(
            MarketDataProviderPort twelveData,
            MarketDataProviderPort eodhd,
            String configuredProviders) {
        Map<String, MarketDataProviderPort> available = Map.of(TWELVE_DATA, twelveData, EODHD, eodhd);
        this.providers = parse(configuredProviders, available);
    }

    @Override
    public Uni<BigDecimal> fetchSpotPrice(String symbol) {
        return fetchSpotPriceWithProvider(symbol).map(MarketDataProviderResult::value);
    }

    @Override
    public Uni<BigDecimal> fetchFxRate(Currency base, Currency quote) {
        return fetchFxRateWithProvider(base, quote).map(MarketDataProviderResult::value);
    }

    @Override
    public Uni<List<PriceHistoryEntry>> fetchPriceHistory(String symbol, LocalDate from, LocalDate to) {
        return fetchPriceHistoryWithProvider(symbol, from, to).map(MarketDataProviderResult::value);
    }

    @Override
    public Uni<List<FxRateEntry>> fetchFxHistory(Currency base, Currency quote, LocalDate from, LocalDate to) {
        return fetchFxHistoryWithProvider(base, quote, from, to).map(MarketDataProviderResult::value);
    }

    @Override
    public Uni<MarketDataProviderResult<BigDecimal>> fetchSpotPriceWithProvider(String symbol) {
        return route(symbol, provider -> provider.fetchSpotPrice(symbol));
    }

    @Override
    public Uni<MarketDataProviderResult<BigDecimal>> fetchFxRateWithProvider(Currency base, Currency quote) {
        String symbol = base.name() + "/" + quote.name();
        return route(symbol, provider -> provider.fetchFxRate(base, quote));
    }

    @Override
    public Uni<MarketDataProviderResult<List<PriceHistoryEntry>>> fetchPriceHistoryWithProvider(
            String symbol,
            LocalDate from,
            LocalDate to) {
        return route(symbol, provider -> provider.fetchPriceHistory(symbol, from, to));
    }

    @Override
    public Uni<MarketDataProviderResult<List<FxRateEntry>>> fetchFxHistoryWithProvider(
            Currency base,
            Currency quote,
            LocalDate from,
            LocalDate to) {
        String symbol = base.name() + "/" + quote.name();
        return route(symbol, provider -> provider.fetchFxHistory(base, quote, from, to));
    }

    private <T> Uni<MarketDataProviderResult<T>> route(
            String symbol,
            Function<MarketDataProviderPort, Uni<T>> operation) {
        return Uni.createFrom().deferred(() -> attempt(symbol, operation, 0, new ArrayList<>()));
    }

    private <T> Uni<MarketDataProviderResult<T>> attempt(
            String symbol,
            Function<MarketDataProviderPort, Uni<T>> operation,
            int index,
            List<MarketDataProviderError.ProviderException> failures) {
        if (index >= providers.size()) {
            return Uni.createFrom().failure(exhausted(symbol, failures));
        }
        Provider selected = providers.get(index);
        LOG.infof("Attempting market data provider=%s symbol=%s", selected.id(), symbol);
        return operation.apply(selected.adapter())
                .map(value -> {
                    LOG.infof("Market data provider succeeded provider=%s symbol=%s", selected.id(), symbol);
                    return new MarketDataProviderResult<>(selected.id(), value);
                })
                .onFailure(MarketDataProviderError.ProviderException.class)
                .recoverWithUni(failure -> {
                    MarketDataProviderError.ProviderException typed =
                            (MarketDataProviderError.ProviderException) failure;
                    failures.add(typed);
                    LOG.warnf("Market data provider failed provider=%s symbol=%s category=%s; trying fallback",
                            selected.id(), symbol, failure.getClass().getSimpleName());
                    return attempt(symbol, operation, index + 1, failures);
                });
    }

    private MarketDataProviderError.ProviderException exhausted(
            String symbol,
            List<MarketDataProviderError.ProviderException> failures) {
        if (failures.size() == 1) {
            return failures.getFirst();
        }
        boolean allRateLimited = failures.stream()
                .allMatch(MarketDataProviderError.RateLimited.class::isInstance);
        if (allRateLimited) {
            Duration earliest = failures.stream()
                    .map(MarketDataProviderError.RateLimited.class::cast)
                    .map(MarketDataProviderError.RateLimited::retryAfter)
                    .min(Duration::compareTo)
                    .orElse(Duration.ofMinutes(1));
            return new MarketDataProviderError.RateLimited(symbol, earliest);
        }
        return new MarketDataProviderError.AllProvidersFailed(symbol, failures);
    }

    private List<Provider> parse(String configured, Map<String, MarketDataProviderPort> available) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("application.market-data.providers must not be empty");
        }
        List<Provider> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : configured.split(",")) {
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (id.isBlank()) {
                throw new IllegalArgumentException("application.market-data.providers contains an empty provider");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Duplicate market data provider: " + id);
            }
            MarketDataProviderPort adapter = available.get(id);
            if (adapter == null) {
                throw new IllegalArgumentException("Unknown market data provider: " + id);
            }
            ordered.add(new Provider(id, adapter));
        }
        return List.copyOf(ordered);
    }

    private void validateCredentials(Config config) {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(TWELVE_DATA, "application.market-data.twelve-data.api-key");
        keys.put(EODHD, "application.market-data.eodhd.api-key");
        for (Provider provider : providers) {
            String property = keys.get(provider.id());
            String value = config.getOptionalValue(property, String.class).orElse("");
            if (value.isBlank() || "not-configured".equalsIgnoreCase(value.trim())) {
                throw new IllegalArgumentException("Missing API key for configured market data provider: "
                        + provider.id());
            }
        }
    }

    private record Provider(String id, MarketDataProviderPort adapter) {
    }
}

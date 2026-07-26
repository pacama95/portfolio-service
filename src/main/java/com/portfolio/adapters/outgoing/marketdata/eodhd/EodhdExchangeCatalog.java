package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.portfolio.adapters.common.ReactiveRateLimiter;
import com.portfolio.adapters.outgoing.marketdata.adapter.MarketDataRateLimiters;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
class EodhdExchangeCatalog {

    private static final Logger LOG = Logger.getLogger(EodhdExchangeCatalog.class);
    private static final String CATALOG_SYMBOL = "exchange-catalog";

    private final EodhdClient client;
    private final EodhdReferenceDataStore store;
    private final String apiKey;
    private final ReactiveRateLimiter rateLimiter;
    private final Duration ttl;

    private volatile Uni<List<EodhdExchange>> refreshInFlight;

    EodhdExchangeCatalog(
            @RestClient EodhdClient client,
            EodhdReferenceDataStore store,
            @ConfigProperty(name = "application.market-data.eodhd.api-key") String apiKey,
            @Named(MarketDataRateLimiters.EODHD) ReactiveRateLimiter rateLimiter,
            @ConfigProperty(name = "application.market-data.eodhd.exchange-catalog-ttl", defaultValue = "PT24H")
            Duration ttl) {
        this.client = client;
        this.store = store;
        this.apiKey = apiKey;
        this.rateLimiter = rateLimiter;
        this.ttl = ttl;
    }

    Uni<List<EodhdExchange>> exchanges() {
        return store.findExchanges().flatMap(stale -> store.findLatestExchangeFetchTime()
                .flatMap(latest -> {
                    boolean fresh = !stale.isEmpty()
                            && latest.filter(time -> time.plus(ttl).isAfter(OffsetDateTime.now())).isPresent();
                    return fresh ? Uni.createFrom().item(stale) : refresh(stale);
                }));
    }

    private synchronized Uni<List<EodhdExchange>> refresh(List<EodhdExchange> stale) {
        if (refreshInFlight != null) {
            return refreshInFlight;
        }
        OffsetDateTime fetchedAt = OffsetDateTime.now();
        Uni<List<EodhdExchange>> refresh = rateLimiter.acquire()
                .onFailure(ReactiveRateLimiter.RateLimitExceededException.class)
                .transform(failure -> new MarketDataProviderError.RateLimited(
                        CATALOG_SYMBOL,
                        ((ReactiveRateLimiter.RateLimitExceededException) failure).retryAfter()))
                .chain(() -> client.exchanges(apiKey, "json"))
                .onFailure().transform(failure -> EodhdFailureMapper.translate(failure, CATALOG_SYMBOL))
                .map(response -> toSnapshot(response, fetchedAt))
                .chain(snapshot -> store.replaceExchanges(snapshot, fetchedAt).replaceWith(snapshot))
                .onItem().invoke(snapshot -> LOG.infof("EODHD exchange catalog refreshed entries=%d", snapshot.size()))
                .onFailure().recoverWithUni(failure -> {
                    if (!stale.isEmpty()) {
                        LOG.warnf("EODHD exchange catalog refresh failed; using stale entries=%d failure=%s",
                                stale.size(), failure.getClass().getSimpleName());
                        return Uni.createFrom().item(stale);
                    }
                    Throwable translated = EodhdFailureMapper.translate(failure, CATALOG_SYMBOL);
                    if (translated instanceof MarketDataProviderError.ProviderException) {
                        return Uni.createFrom().failure(translated);
                    }
                    return Uni.createFrom().failure(new MarketDataProviderError.ProviderUnavailable(CATALOG_SYMBOL));
                })
                .memoize().indefinitely();
        refreshInFlight = refresh;
        return refresh.onTermination().invoke(() -> clearRefresh(refresh));
    }

    private synchronized void clearRefresh(Uni<List<EodhdExchange>> completed) {
        if (refreshInFlight == completed) {
            refreshInFlight = null;
        }
    }

    private List<EodhdExchange> toSnapshot(List<EodhdExchangeResponse> response, OffsetDateTime fetchedAt) {
        if (response == null || response.isEmpty()) {
            throw new MarketDataProviderError.MissingData(CATALOG_SYMBOL, "exchanges");
        }
        return response.stream().map(exchange -> {
            if (exchange.code() == null || exchange.code().isBlank()) {
                throw new MarketDataProviderError.MissingData(CATALOG_SYMBOL, "Code");
            }
            if (exchange.name() == null || exchange.name().isBlank()) {
                throw new MarketDataProviderError.MissingData(CATALOG_SYMBOL, "Name");
            }
            return new EodhdExchange(
                    exchange.code().trim().toUpperCase(Locale.ROOT),
                    exchange.name().trim(),
                    trimToNull(exchange.operatingMic()),
                    trimToNull(exchange.country()),
                    trimToNull(exchange.currency()),
                    trimToNull(exchange.countryIso2()),
                    trimToNull(exchange.countryIso3()),
                    true,
                    fetchedAt);
        }).toList();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

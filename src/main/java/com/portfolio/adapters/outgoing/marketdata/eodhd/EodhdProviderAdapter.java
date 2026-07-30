package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.portfolio.adapters.common.ReactiveRateLimiter;
import com.portfolio.adapters.outgoing.marketdata.adapter.MarketDataProviderAdapter;
import com.portfolio.adapters.outgoing.marketdata.adapter.MarketDataRateLimiters;
import com.portfolio.adapters.outgoing.marketdata.adapter.ProviderCalls;
import com.portfolio.core.application.service.CurrencyResolver;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import com.portfolio.core.ports.outgoing.MarketDataProviderPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@ApplicationScoped
@MarketDataProviderAdapter("eodhd")
public class EodhdProviderAdapter implements MarketDataProviderPort {

    private static final Logger LOG = Logger.getLogger(EodhdProviderAdapter.class);
    private static final String PRICE_CURRENCY_OVERRIDE_PREFIX =
            "application.market-data.eodhd.exchange-price-currency-overrides.";

    private final EodhdClient client;
    private final EodhdSymbolResolver symbolResolver;
    private final CurrencyResolver currencyResolver;
    private final String apiKey;
    private final ReactiveRateLimiter rateLimiter;
    private final Map<String, String> exchangeCurrencyOverrides;

    @Inject
    EodhdProviderAdapter(
            @RestClient EodhdClient client,
            EodhdSymbolResolver symbolResolver,
            CurrencyResolver currencyResolver,
            @ConfigProperty(name = "application.market-data.eodhd.api-key") String apiKey,
            @Named(MarketDataRateLimiters.EODHD) ReactiveRateLimiter rateLimiter,
            Config config) {
        this(client, symbolResolver, currencyResolver, apiKey, rateLimiter, extractCurrencyOverrides(config));
    }

    EodhdProviderAdapter(
            EodhdClient client,
            EodhdSymbolResolver symbolResolver,
            CurrencyResolver currencyResolver,
            String apiKey,
            ReactiveRateLimiter rateLimiter,
            Map<String, String> exchangeCurrencyOverrides) {
        this.client = client;
        this.symbolResolver = symbolResolver;
        this.currencyResolver = currencyResolver;
        this.apiKey = apiKey;
        this.rateLimiter = rateLimiter;
        this.exchangeCurrencyOverrides = normalizeOverrides(exchangeCurrencyOverrides);
    }

    @Override
    public Uni<BigDecimal> fetchSpotPrice(String symbol) {
        return symbolResolver.resolve(symbol).flatMap(resolved -> {
            CurrencyResolver.Resolution currency = resolveCurrency(resolved);
            return throttled(resolved.canonicalSymbol(),
                    () -> client.realTime(resolved.providerSymbol(), apiKey, "json"))
                    .map(response -> requiredClose(response, resolved.canonicalSymbol())
                            .multiply(currency.multiplier()));
        });
    }

    @Override
    public Uni<BigDecimal> fetchFxRate(Currency base, Currency quote) {
        String providerSymbol = fxSymbol(base, quote);
        String canonical = base.name() + "/" + quote.name();
        return throttled(canonical, () -> client.realTime(providerSymbol, apiKey, "json"))
                .map(response -> requiredClose(response, canonical));
    }

    @Override
    public Uni<List<PriceHistoryEntry>> fetchPriceHistory(String symbol, LocalDate from, LocalDate to) {
        return symbolResolver.resolve(symbol).flatMap(resolved -> {
            CurrencyResolver.Resolution currency = resolveCurrency(resolved);
            return throttled(resolved.canonicalSymbol(),
                    () -> client.eod(
                            resolved.providerSymbol(),
                            apiKey,
                            "json",
                            from.toString(),
                            to.toString(),
                            "d",
                            "a"))
                    .map(response -> priceHistory(resolved.canonicalSymbol(), response, currency));
        });
    }

    @Override
    public Uni<List<FxRateEntry>> fetchFxHistory(
            Currency base,
            Currency quote,
            LocalDate from,
            LocalDate to) {
        String providerSymbol = fxSymbol(base, quote);
        String canonical = base.name() + "/" + quote.name();
        return throttled(canonical,
                () -> client.eod(
                        providerSymbol,
                        apiKey,
                        "json",
                        from.toString(),
                        to.toString(),
                        "d",
                        "a"))
                .map(response -> fxHistory(base, quote, canonical, response));
    }

    private List<PriceHistoryEntry> priceHistory(
            String canonical,
            List<EodhdEodBarResponse> response,
            CurrencyResolver.Resolution currency) {
        if (response == null) {
            throw new MarketDataProviderError.MissingData(canonical, "history");
        }
        failIfHistoryWarning(canonical, response);
        OffsetDateTime fetchedAt = OffsetDateTime.now();
        List<PriceHistoryEntry> entries = new ArrayList<>();
        for (EodhdEodBarResponse bar : response) {
            LocalDate date = requiredDate(bar, canonical);
            if (bar.close() == null) {
                throw new MarketDataProviderError.MissingData(canonical, "close");
            }
            BigDecimal close = bar.close().multiply(currency.multiplier());
            BigDecimal adjusted = (bar.adjustedClose() == null ? bar.close() : bar.adjustedClose())
                    .multiply(currency.multiplier());
            entries.add(new PriceHistoryEntry(
                    canonical, date, close, adjusted, currency.currency(), fetchedAt));
        }
        entries.sort(Comparator.comparing(PriceHistoryEntry::priceDate));
        return List.copyOf(entries);
    }

    private List<FxRateEntry> fxHistory(
            Currency base,
            Currency quote,
            String canonical,
            List<EodhdEodBarResponse> response) {
        if (response == null) {
            throw new MarketDataProviderError.MissingData(canonical, "history");
        }
        failIfHistoryWarning(canonical, response);
        OffsetDateTime fetchedAt = OffsetDateTime.now();
        List<FxRateEntry> entries = new ArrayList<>();
        for (EodhdEodBarResponse bar : response) {
            LocalDate date = requiredDate(bar, canonical);
            if (bar.close() == null) {
                throw new MarketDataProviderError.MissingData(canonical, "close");
            }
            entries.add(new FxRateEntry(base, quote, date, bar.close(), fetchedAt));
        }
        entries.sort(Comparator.comparing(FxRateEntry::rateDate));
        return List.copyOf(entries);
    }

    /**
     * EODHD may return HTTP 200 and attach a subscription warning to the final bar, or return a
     * warning-only object when the entire requested range is unavailable. Accepting that response
     * would make MarketDataService mark a truncated range as completely covered.
     */
    private void failIfHistoryWarning(String canonical, List<EodhdEodBarResponse> response) {
        response.stream()
                .filter(java.util.Objects::nonNull)
                .map(EodhdEodBarResponse::warning)
                .filter(warning -> warning != null && !warning.isBlank())
                .findFirst()
                .ifPresent(warning -> {
                    String message = warning.trim();
                    LOG.warnf("EODHD history response rejected symbol=%s warning=%s", canonical, message);
                    throw new MarketDataProviderError.ErrorResponse(canonical, message);
                });
    }

    private LocalDate requiredDate(EodhdEodBarResponse bar, String symbol) {
        if (bar == null || bar.date() == null || bar.date().isBlank()) {
            throw new MarketDataProviderError.MissingData(symbol, "date");
        }
        try {
            return LocalDate.parse(bar.date().trim());
        } catch (DateTimeParseException failure) {
            throw new MarketDataProviderError.MissingData(symbol, "date");
        }
    }

    private BigDecimal requiredClose(EodhdRealTimeResponse response, String symbol) {
        if (response == null || response.close() == null) {
            throw new MarketDataProviderError.MissingData(symbol, "close");
        }
        return response.close();
    }

    private CurrencyResolver.Resolution resolveCurrency(EodhdResolvedSymbol resolved) {
        String rawCurrency = exchangeCurrencyOverrides.getOrDefault(
                resolved.exchangeCode(), resolved.rawCurrency());
        if (rawCurrency == null || rawCurrency.isBlank()) {
            throw new MarketDataProviderError.MissingData(resolved.canonicalSymbol(), "currency");
        }
        return currencyResolver.resolve(rawCurrency)
                .orElseThrow(() -> new MarketDataProviderError.UnsupportedCurrency(
                        resolved.canonicalSymbol(), rawCurrency));
    }

    private <T> Uni<T> throttled(String canonicalSymbol, Supplier<Uni<T>> call) {
        return ProviderCalls.throttled(rateLimiter, canonicalSymbol, call);
    }

    private String fxSymbol(Currency base, Currency quote) {
        return base.name() + quote.name() + ".FOREX";
    }

    private static Map<String, String> extractCurrencyOverrides(Config config) {
        Map<String, String> values = new HashMap<>();
        for (String propertyName : config.getPropertyNames()) {
            if (propertyName.startsWith(PRICE_CURRENCY_OVERRIDE_PREFIX)) {
                String exchange = propertyName.substring(PRICE_CURRENCY_OVERRIDE_PREFIX.length());
                config.getOptionalValue(propertyName, String.class)
                        .ifPresent(value -> values.put(exchange, value));
            }
        }
        return values;
    }

    private static Map<String, String> normalizeOverrides(Map<String, String> overrides) {
        Map<String, String> normalized = new HashMap<>();
        overrides.forEach((exchange, currency) -> normalized.put(
                exchange.trim().toUpperCase(Locale.ROOT), currency.trim()));
        return Map.copyOf(normalized);
    }
}

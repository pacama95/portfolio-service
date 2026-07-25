package com.portfolio.adapters.outgoing.marketdata.adapter;

import com.portfolio.adapters.common.ReactiveRateLimiter;
import com.portfolio.adapters.outgoing.marketdata.client.TwelveDataClient;
import com.portfolio.adapters.outgoing.marketdata.client.TwelveDataResponse;
import com.portfolio.adapters.outgoing.marketdata.client.TwelveDataTimeSeriesResponse;
import com.portfolio.core.application.service.CurrencyResolver;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import com.portfolio.core.ports.outgoing.MarketDataProviderPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@ApplicationScoped
public class TwelveDataProviderAdapter implements MarketDataProviderPort {

    private static final Logger LOG = Logger.getLogger(TwelveDataProviderAdapter.class);

    /** Twelve Data error code for an exhausted credit budget; credits reset every minute. */
    private static final int RATE_LIMIT_CODE = 429;
    private static final Duration PROVIDER_RATE_LIMIT_RETRY_AFTER = Duration.ofMinutes(1);

    private final TwelveDataClient client;
    private final String apiKey;
    private final CurrencyResolver currencyResolver;
    private final ReactiveRateLimiter rateLimiter;

    public TwelveDataProviderAdapter(
            @RestClient TwelveDataClient client,
            @ConfigProperty(name = "application.market-data.twelve-data.api-key") String apiKey,
            CurrencyResolver currencyResolver,
            @Named(MarketDataRateLimiters.TWELVE_DATA) ReactiveRateLimiter rateLimiter) {
        this.client = client;
        this.apiKey = apiKey;
        this.currencyResolver = currencyResolver;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Uni<BigDecimal> fetchSpotPrice(String symbol) {
        LOG.infof("Twelve Data fetchSpotPrice symbol=%s", symbol);
        return throttled(symbol, () -> client.price(symbol, apiKey)
                .map(response -> {
                    failIfProviderError(response, symbol);
                    if (response.price() == null || response.price().isBlank()) {
                        LOG.errorf("Twelve Data missing price field symbol=%s", symbol);
                        throw new MarketDataProviderError.MissingData(symbol, "price");
                    }
                    return new BigDecimal(response.price());
                }));
    }

    @Override
    public Uni<BigDecimal> fetchFxRate(Currency base, Currency quote) {
        String symbol = base.name() + "/" + quote.name();
        LOG.infof("Twelve Data fetchFxRate symbol=%s", symbol);
        return throttled(symbol, () -> client.exchangeRate(symbol, apiKey)
                .map(response -> {
                    failIfProviderError(response, symbol);
                    if (response.rate() == null || response.rate().isBlank()) {
                        LOG.errorf("Twelve Data missing rate field symbol=%s", symbol);
                        throw new MarketDataProviderError.MissingData(symbol, "rate");
                    }
                    return new BigDecimal(response.rate());
                }));
    }

    @Override
    public Uni<List<PriceHistoryEntry>> fetchPriceHistory(String symbol, LocalDate from, LocalDate to) {
        LOG.infof("Twelve Data fetchPriceHistory symbol=%s from=%s to=%s", symbol, from, to);
        return throttled(symbol, () -> client.timeSeries(symbol, "1day", from.toString(), endDateParam(to), apiKey)
                .map(response -> {
                    failIfProviderError(response, symbol);
                    List<PriceHistoryEntry> entries = parsePriceSeries(symbol, response);
                    if (entries.isEmpty()) {
                        LOG.warnf("Twelve Data returned empty price series symbol=%s from=%s to=%s",
                                symbol, from, to);
                    }
                    return entries;
                }));
    }

    @Override
    public Uni<List<FxRateEntry>> fetchFxHistory(Currency base, Currency quote, LocalDate from, LocalDate to) {
        String symbol = base.name() + "/" + quote.name();
        LOG.infof("Twelve Data fetchFxHistory symbol=%s from=%s to=%s", symbol, from, to);
        return throttled(symbol, () -> client.timeSeries(symbol, "1day", from.toString(), endDateParam(to), apiKey)
                .map(response -> {
                    failIfProviderError(response, symbol);
                    List<FxRateEntry> entries = parseFxSeries(base, quote, response);
                    if (entries.isEmpty()) {
                        LOG.warnf("Twelve Data returned empty FX series symbol=%s from=%s to=%s",
                                symbol, from, to);
                    }
                    return entries;
                }));
    }

    /**
     * Twelve Data's {@code end_date} is exclusive for the {@code 1day} interval — it is read as an
     * instant at midnight, so a bar stamped with that same date falls outside the window. Every
     * caller here passes an inclusive {@code to}, so shift it forward a day to keep that bar.
     * Without this the newest day of any requested range is silently never returned, which leaves
     * a permanent hole once {@code MarketDataService} records the range as covered.
     */
    private static String endDateParam(LocalDate to) {
        return to.plusDays(1).toString();
    }

    /**
     * Every provider call waits for a shared per-provider credit slot first; a saturated queue
     * fails fast as {@link MarketDataProviderError.RateLimited} instead of hitting the API.
     */
    private <T> Uni<T> throttled(String symbol, Supplier<Uni<T>> call) {
        return rateLimiter.acquire()
                .onFailure(ReactiveRateLimiter.RateLimitExceededException.class)
                .transform(failure -> new MarketDataProviderError.RateLimited(
                        symbol, ((ReactiveRateLimiter.RateLimitExceededException) failure).retryAfter()))
                .chain(() -> call.get());
    }

    /**
     * Twelve Data returns HTTP 200 with {@code status=error} for rate limits, invalid symbols,
     * etc. Must throw rather than warn-and-continue: an error response parses to zero entries,
     * indistinguishable from a legitimate empty range (holiday/weekend), and the caller
     * (MarketDataService) would otherwise cache the error as "confirmed no data" forever.
     */
    private void failIfProviderError(TwelveDataResponse response, String symbol) {
        if (response == null || !response.isError()) {
            return;
        }
        String message = response.message() != null ? response.message() : "unknown";
        LOG.warnf("Twelve Data error response symbol=%s code=%s message=%s",
                symbol, response.code(), message);
        if (response.code() != null && response.code() == RATE_LIMIT_CODE) {
            throw new MarketDataProviderError.RateLimited(symbol, PROVIDER_RATE_LIMIT_RETRY_AFTER);
        }
        throw new MarketDataProviderError.ErrorResponse(symbol, message);
    }

    private List<PriceHistoryEntry> parsePriceSeries(String symbol, TwelveDataTimeSeriesResponse response) {
        List<PriceHistoryEntry> entries = new ArrayList<>();
        if (response.values() == null) {
            return entries;
        }
        OffsetDateTime now = OffsetDateTime.now();
        String rawCurrency = response.meta() != null ? response.meta().currency() : null;
        CurrencyResolver.Resolution resolution = resolveCurrency(symbol, rawCurrency);
        for (TwelveDataTimeSeriesResponse.Bar bar : response.values()) {
            LocalDate date = LocalDate.parse(bar.datetime().substring(0, 10));
            BigDecimal close = new BigDecimal(bar.close()).multiply(resolution.multiplier());
            entries.add(new PriceHistoryEntry(symbol, date, close, close, resolution.currency(), now));
        }
        return entries;
    }

    /**
     * A provider that omits the currency entirely (no signal at all) defaults to USD; a
     * provider that reports a currency code we don't recognize fails loudly instead of silently
     * mislabeling the price (see {@link CurrencyResolver} for how codes, including minor-unit
     * quotes like pence sterling, are resolved).
     */
    private CurrencyResolver.Resolution resolveCurrency(String symbol, String rawCurrency) {
        if (rawCurrency == null || rawCurrency.isBlank()) {
            return new CurrencyResolver.Resolution(Currency.USD, BigDecimal.ONE);
        }
        return currencyResolver.resolve(rawCurrency.trim())
                .orElseThrow(() -> {
                    LOG.errorf("Twelve Data unsupported currency symbol=%s currency=%s", symbol, rawCurrency);
                    return new MarketDataProviderError.UnsupportedCurrency(symbol, rawCurrency);
                });
    }

    private List<FxRateEntry> parseFxSeries(Currency base, Currency quote, TwelveDataTimeSeriesResponse response) {
        List<FxRateEntry> entries = new ArrayList<>();
        if (response.values() == null) {
            return entries;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (TwelveDataTimeSeriesResponse.Bar bar : response.values()) {
            LocalDate date = LocalDate.parse(bar.datetime().substring(0, 10));
            BigDecimal rate = new BigDecimal(bar.close());
            entries.add(new FxRateEntry(base, quote, date, rate, now));
        }
        return entries;
    }
}

package com.portfolio.adapters.outgoing.marketdata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.adapters.outgoing.marketdata.client.TwelveDataClient;
import com.portfolio.core.application.service.CurrencyResolver;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import com.portfolio.core.ports.outgoing.MarketDataProviderPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class TwelveDataProviderAdapter implements MarketDataProviderPort {

    private static final Logger LOG = Logger.getLogger(TwelveDataProviderAdapter.class);

    private final TwelveDataClient client;
    private final String apiKey;
    private final CurrencyResolver currencyResolver;

    public TwelveDataProviderAdapter(
            @RestClient TwelveDataClient client,
            @ConfigProperty(name = "application.market-data.twelve-data.api-key") String apiKey,
            CurrencyResolver currencyResolver) {
        this.client = client;
        this.apiKey = apiKey;
        this.currencyResolver = currencyResolver;
    }

    @Override
    public Uni<BigDecimal> fetchSpotPrice(String symbol) {
        LOG.infof("Twelve Data fetchSpotPrice symbol=%s", symbol);
        return client.price(symbol, apiKey)
                .map(node -> {
                    failIfProviderError(node, symbol);
                    JsonNode price = node.get("price");
                    if (price == null || price.isNull()) {
                        LOG.errorf("Twelve Data missing price field symbol=%s", symbol);
                        throw new MarketDataProviderError.MissingData(symbol, "price");
                    }
                    return new BigDecimal(price.asText());
                });
    }

    @Override
    public Uni<BigDecimal> fetchFxRate(Currency base, Currency quote) {
        String symbol = base.name() + "/" + quote.name();
        LOG.infof("Twelve Data fetchFxRate symbol=%s", symbol);
        return client.exchangeRate(symbol, apiKey)
                .map(node -> {
                    failIfProviderError(node, symbol);
                    JsonNode rate = node.get("rate");
                    if (rate == null || rate.isNull()) {
                        LOG.errorf("Twelve Data missing rate field symbol=%s", symbol);
                        throw new MarketDataProviderError.MissingData(symbol, "rate");
                    }
                    return new BigDecimal(rate.asText());
                });
    }

    @Override
    public Uni<List<PriceHistoryEntry>> fetchPriceHistory(String symbol, LocalDate from, LocalDate to) {
        LOG.infof("Twelve Data fetchPriceHistory symbol=%s from=%s to=%s", symbol, from, to);
        return client.timeSeries(symbol, "1day", from.toString(), to.toString(), apiKey)
                .map(node -> {
                    failIfProviderError(node, symbol);
                    List<PriceHistoryEntry> entries = parsePriceSeries(symbol, node);
                    if (entries.isEmpty()) {
                        LOG.warnf("Twelve Data returned empty price series symbol=%s from=%s to=%s",
                                symbol, from, to);
                    }
                    return entries;
                });
    }

    @Override
    public Uni<List<FxRateEntry>> fetchFxHistory(Currency base, Currency quote, LocalDate from, LocalDate to) {
        String symbol = base.name() + "/" + quote.name();
        LOG.infof("Twelve Data fetchFxHistory symbol=%s from=%s to=%s", symbol, from, to);
        return client.timeSeries(symbol, "1day", from.toString(), to.toString(), apiKey)
                .map(node -> {
                    failIfProviderError(node, symbol);
                    List<FxRateEntry> entries = parseFxSeries(base, quote, node);
                    if (entries.isEmpty()) {
                        LOG.warnf("Twelve Data returned empty FX series symbol=%s from=%s to=%s",
                                symbol, from, to);
                    }
                    return entries;
                });
    }

    /**
     * Twelve Data returns HTTP 200 with {@code status=error} for rate limits, invalid symbols,
     * etc. Must throw rather than warn-and-continue: an error response parses to zero entries,
     * indistinguishable from a legitimate empty range (holiday/weekend), and the caller
     * (MarketDataService) would otherwise cache the error as "confirmed no data" forever.
     */
    private void failIfProviderError(JsonNode node, String symbol) {
        if (node != null && node.hasNonNull("status") && "error".equalsIgnoreCase(node.get("status").asText())) {
            String message = node.hasNonNull("message") ? node.get("message").asText() : "unknown";
            LOG.warnf("Twelve Data error response symbol=%s message=%s", symbol, message);
            throw new MarketDataProviderError.ErrorResponse(symbol, message);
        }
    }

    private List<PriceHistoryEntry> parsePriceSeries(String symbol, JsonNode node) {
        List<PriceHistoryEntry> entries = new ArrayList<>();
        JsonNode values = node.get("values");
        if (values == null || !values.isArray()) {
            return entries;
        }
        OffsetDateTime now = OffsetDateTime.now();
        JsonNode meta = node.get("meta");
        String rawCurrency = meta != null && meta.get("currency") != null ? meta.get("currency").asText() : null;
        CurrencyResolver.Resolution resolution = resolveCurrency(symbol, rawCurrency);
        for (JsonNode value : values) {
            LocalDate date = LocalDate.parse(value.get("datetime").asText().substring(0, 10));
            BigDecimal close = new BigDecimal(value.get("close").asText()).multiply(resolution.multiplier());
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

    private List<FxRateEntry> parseFxSeries(Currency base, Currency quote, JsonNode node) {
        List<FxRateEntry> entries = new ArrayList<>();
        JsonNode values = node.get("values");
        if (values == null || !values.isArray()) {
            return entries;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (JsonNode value : values) {
            LocalDate date = LocalDate.parse(value.get("datetime").asText().substring(0, 10));
            BigDecimal rate = new BigDecimal(value.get("close").asText());
            entries.add(new FxRateEntry(base, quote, date, rate, now));
        }
        return entries;
    }
}

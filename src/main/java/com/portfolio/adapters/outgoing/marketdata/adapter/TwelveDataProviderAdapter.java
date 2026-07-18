package com.portfolio.adapters.outgoing.marketdata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.adapters.outgoing.marketdata.client.TwelveDataClient;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
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

    public TwelveDataProviderAdapter(
            @RestClient TwelveDataClient client,
            @ConfigProperty(name = "application.market-data.twelve-data.api-key") String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
    }

    @Override
    public Uni<BigDecimal> fetchSpotPrice(String symbol) {
        LOG.infof("Twelve Data fetchSpotPrice symbol=%s", symbol);
        return client.price(symbol, apiKey)
                .map(node -> {
                    warnIfProviderError(node, symbol);
                    JsonNode price = node.get("price");
                    if (price == null || price.isNull()) {
                        LOG.errorf("Twelve Data missing price field symbol=%s", symbol);
                        throw new IllegalStateException("No price for symbol " + symbol);
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
                    warnIfProviderError(node, symbol);
                    JsonNode rate = node.get("rate");
                    if (rate == null || rate.isNull()) {
                        LOG.errorf("Twelve Data missing rate field symbol=%s", symbol);
                        throw new IllegalStateException("No FX rate for " + symbol);
                    }
                    return new BigDecimal(rate.asText());
                });
    }

    @Override
    public Uni<List<PriceHistoryEntry>> fetchPriceHistory(String symbol, LocalDate from, LocalDate to) {
        LOG.infof("Twelve Data fetchPriceHistory symbol=%s from=%s to=%s", symbol, from, to);
        return client.timeSeries(symbol, "1day", from.toString(), to.toString(), apiKey)
                .map(node -> {
                    warnIfProviderError(node, symbol);
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
                    warnIfProviderError(node, symbol);
                    List<FxRateEntry> entries = parseFxSeries(base, quote, node);
                    if (entries.isEmpty()) {
                        LOG.warnf("Twelve Data returned empty FX series symbol=%s from=%s to=%s",
                                symbol, from, to);
                    }
                    return entries;
                });
    }

    private void warnIfProviderError(JsonNode node, String symbol) {
        if (node != null && node.hasNonNull("status") && "error".equalsIgnoreCase(node.get("status").asText())) {
            String message = node.hasNonNull("message") ? node.get("message").asText() : "unknown";
            LOG.warnf("Twelve Data error response symbol=%s message=%s", symbol, message);
        }
    }

    private List<PriceHistoryEntry> parsePriceSeries(String symbol, JsonNode node) {
        List<PriceHistoryEntry> entries = new ArrayList<>();
        JsonNode values = node.get("values");
        if (values == null || !values.isArray()) {
            return entries;
        }
        OffsetDateTime now = OffsetDateTime.now();
        Currency currency = Currency.USD;
        JsonNode meta = node.get("meta");
        if (meta != null && meta.get("currency") != null) {
            try {
                currency = Currency.valueOf(meta.get("currency").asText());
            } catch (IllegalArgumentException ignored) {
                LOG.warnf("Twelve Data unknown currency for symbol=%s; defaulting to USD", symbol);
            }
        }
        for (JsonNode value : values) {
            LocalDate date = LocalDate.parse(value.get("datetime").asText().substring(0, 10));
            BigDecimal close = new BigDecimal(value.get("close").asText());
            entries.add(new PriceHistoryEntry(symbol, date, close, close, currency, now));
        }
        return entries;
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

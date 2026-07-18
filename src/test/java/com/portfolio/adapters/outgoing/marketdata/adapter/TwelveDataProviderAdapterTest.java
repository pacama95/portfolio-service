package com.portfolio.adapters.outgoing.marketdata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.adapters.outgoing.marketdata.client.TwelveDataClient;
import com.portfolio.core.application.service.CurrencyResolver;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class TwelveDataProviderAdapterTest {

    private static final String API_KEY = "test-api-key";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TwelveDataClient client = Mockito.mock(TwelveDataClient.class);
    private final CurrencyResolver currencyResolver = new CurrencyResolver(Map.of(
            "GBX", "GBP:0.01",
            "GBp", "GBP:0.01"));
    private TwelveDataProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new TwelveDataProviderAdapter(client, API_KEY, currencyResolver);
    }

    @Test
    void givenPriceResponse_whenFetchSpotPrice_thenReturnsPrice() throws Exception {
        JsonNode node = MAPPER.readTree("{\"price\":\"123.45\"}");
        when(client.price("AAPL", API_KEY)).thenReturn(Uni.createFrom().item(node));

        BigDecimal price = adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(0, new BigDecimal("123.45").compareTo(price));
    }

    @Test
    void givenMissingPrice_whenFetchSpotPrice_thenThrows() throws Exception {
        JsonNode node = MAPPER.readTree("{\"price\":null}");
        when(client.price("AAPL", API_KEY)).thenReturn(Uni.createFrom().item(node));

        adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    @Test
    void givenErrorStatusResponse_whenFetchSpotPrice_thenThrows() throws Exception {
        JsonNode node = MAPPER.readTree("{\"status\":\"error\",\"message\":\"rate limit exceeded\"}");
        when(client.price("AAPL", API_KEY)).thenReturn(Uni.createFrom().item(node));

        adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ErrorResponse.class);
    }

    @Test
    void givenFxResponse_whenFetchFxRate_thenReturnsRate() throws Exception {
        JsonNode node = MAPPER.readTree("{\"rate\":\"1.2345\"}");
        when(client.exchangeRate("EUR/USD", API_KEY)).thenReturn(Uni.createFrom().item(node));

        BigDecimal rate = adapter.fetchFxRate(Currency.EUR, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(0, new BigDecimal("1.2345").compareTo(rate));
    }

    @Test
    void givenMissingFxRate_whenFetchFxRate_thenThrows() throws Exception {
        JsonNode node = MAPPER.readTree("{}");
        when(client.exchangeRate("EUR/USD", API_KEY)).thenReturn(Uni.createFrom().item(node));

        adapter.fetchFxRate(Currency.EUR, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    @Test
    void givenErrorStatusResponse_whenFetchFxRate_thenThrows() throws Exception {
        JsonNode node = MAPPER.readTree("{\"status\":\"error\",\"message\":\"rate limit exceeded\"}");
        when(client.exchangeRate("EUR/USD", API_KEY)).thenReturn(Uni.createFrom().item(node));

        adapter.fetchFxRate(Currency.EUR, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ErrorResponse.class);
    }

    @Test
    void givenTimeSeries_whenFetchPriceHistory_thenParsesEntries() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);
        JsonNode node = MAPPER.readTree("""
                {
                  "meta": {"currency": "USD"},
                  "values": [
                    {"datetime": "2024-01-01", "close": "100.5"},
                    {"datetime": "2024-01-02", "close": "101.0"}
                  ]
                }
                """);
        when(client.timeSeries("AAPL", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(2, entries.size());
        assertEquals("AAPL", entries.get(0).symbol());
        assertEquals(from, entries.get(0).priceDate());
        assertEquals(Currency.USD, entries.get(0).currency());
        assertEquals(0, new BigDecimal("100.5").compareTo(entries.get(0).closePrice()));
    }

    @Test
    void givenMissingPriceNode_whenFetchSpotPrice_thenThrows() throws Exception {
        JsonNode node = MAPPER.readTree("{}");
        when(client.price("AAPL", API_KEY)).thenReturn(Uni.createFrom().item(node));

        adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    @Test
    void givenNullFxRateNode_whenFetchFxRate_thenThrows() throws Exception {
        JsonNode node = MAPPER.readTree("{\"rate\":null}");
        when(client.exchangeRate("EUR/USD", API_KEY)).thenReturn(Uni.createFrom().item(node));

        adapter.fetchFxRate(Currency.EUR, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    @Test
    void givenErrorStatusResponse_whenFetchPriceHistory_thenThrows() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("{\"status\":\"error\",\"message\":\"rate limit exceeded\"}");
        when(client.timeSeries("AAPL", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        adapter.fetchPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ErrorResponse.class);
    }

    @Test
    void givenErrorStatusResponse_whenFetchFxHistory_thenThrows() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("{\"status\":\"error\",\"message\":\"rate limit exceeded\"}");
        when(client.timeSeries("EUR/USD", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        adapter.fetchFxHistory(Currency.EUR, Currency.USD, from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ErrorResponse.class);
    }

    @Test
    void givenMissingValuesNode_whenFetchPriceHistory_thenReturnsEmptyList() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("{}");
        when(client.timeSeries("AAPL", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertTrue(entries.isEmpty());
    }

    @Test
    void givenNonArrayValues_whenFetchPriceHistory_thenReturnsEmptyList() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("{\"values\":\"not-an-array\"}");
        when(client.timeSeries("AAPL", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertTrue(entries.isEmpty());
    }

    @Test
    void givenMissingMeta_whenFetchPriceHistory_thenDefaultsToUsd() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("""
                {
                  "values": [{"datetime": "2024-01-01", "close": "50"}]
                }
                """);
        when(client.timeSeries("AAPL", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Currency.USD, entries.getFirst().currency());
    }

    @Test
    void givenMissingValuesNode_whenFetchFxHistory_thenReturnsEmptyList() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("{}");
        when(client.timeSeries("EUR/USD", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<FxRateEntry> entries = adapter.fetchFxHistory(Currency.EUR, Currency.USD, from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertTrue(entries.isEmpty());
    }

    @Test
    void givenNonArrayValues_whenFetchFxHistory_thenReturnsEmptyList() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("{\"values\":{}}");
        when(client.timeSeries("EUR/USD", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<FxRateEntry> entries = adapter.fetchFxHistory(Currency.EUR, Currency.USD, from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertTrue(entries.isEmpty());
    }

    @Test
    void givenEmptyValues_whenFetchPriceHistory_thenReturnsEmptyList() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("{\"values\":[]}");
        when(client.timeSeries("AAPL", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertTrue(entries.isEmpty());
    }

    @Test
    void givenUnsupportedCurrencyMeta_whenFetchPriceHistory_thenThrows() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("""
                {
                  "meta": {"currency": "NOT_A_CURRENCY"},
                  "values": [{"datetime": "2024-01-01", "close": "50"}]
                }
                """);
        when(client.timeSeries("AAPL", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        adapter.fetchPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.UnsupportedCurrency.class);
    }

    @Test
    void givenGbxCurrencyMeta_whenFetchPriceHistory_thenScalesToGbp() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("""
                {
                  "meta": {"currency": "GBX"},
                  "values": [{"datetime": "2024-01-01", "close": "12345"}]
                }
                """);
        when(client.timeSeries("LLOY", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("LLOY", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Currency.GBP, entries.getFirst().currency());
        assertEquals(0, new BigDecimal("123.45").compareTo(entries.getFirst().closePrice()));
    }

    @Test
    void givenGbpPenceCurrencyMeta_whenFetchPriceHistory_thenScalesToGbp() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("""
                {
                  "meta": {"currency": "GBp"},
                  "values": [{"datetime": "2024-01-01", "close": "500"}]
                }
                """);
        when(client.timeSeries("LLOY", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("LLOY", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Currency.GBP, entries.getFirst().currency());
        assertEquals(0, new BigDecimal("5.00").compareTo(entries.getFirst().closePrice()));
    }

    @Test
    void givenPlainGbpCurrencyMeta_whenFetchPriceHistory_thenDoesNotRescale() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("""
                {
                  "meta": {"currency": "GBP"},
                  "values": [{"datetime": "2024-01-01", "close": "5.00"}]
                }
                """);
        when(client.timeSeries("BP", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("BP", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Currency.GBP, entries.getFirst().currency());
        assertEquals(0, new BigDecimal("5.00").compareTo(entries.getFirst().closePrice()));
    }

    @Test
    void givenFxTimeSeries_whenFetchFxHistory_thenParsesRates() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);
        JsonNode node = MAPPER.readTree("""
                {
                  "values": [
                    {"datetime": "2024-01-01", "close": "1.10"},
                    {"datetime": "2024-01-02", "close": "1.11"}
                  ]
                }
                """);
        when(client.timeSeries("EUR/USD", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<FxRateEntry> entries = adapter.fetchFxHistory(Currency.EUR, Currency.USD, from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(2, entries.size());
        assertEquals(Currency.EUR, entries.get(0).baseCurrency());
        assertEquals(Currency.USD, entries.get(0).quoteCurrency());
        assertEquals(from, entries.get(0).rateDate());
        assertEquals(0, new BigDecimal("1.10").compareTo(entries.get(0).rate()));
    }
}

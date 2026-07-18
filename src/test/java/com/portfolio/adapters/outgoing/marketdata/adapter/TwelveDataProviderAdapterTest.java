package com.portfolio.adapters.outgoing.marketdata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.adapters.outgoing.marketdata.client.TwelveDataClient;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class TwelveDataProviderAdapterTest {

    private static final String API_KEY = "test-api-key";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TwelveDataClient client = Mockito.mock(TwelveDataClient.class);
    private TwelveDataProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new TwelveDataProviderAdapter(client, API_KEY);
    }

    @Test
    void givenPriceResponse_whenFetchSpotPrice_thenReturnsPrice() throws Exception {
        JsonNode node = MAPPER.readTree("{\"price\":\"123.45\"}");
        when(client.price("AAPL", API_KEY)).thenReturn(Uni.createFrom().item(node));

        BigDecimal price = adapter.fetchSpotPrice("AAPL").await().indefinitely();

        assertEquals(0, new BigDecimal("123.45").compareTo(price));
    }

    @Test
    void givenMissingPrice_whenFetchSpotPrice_thenThrows() throws Exception {
        JsonNode node = MAPPER.readTree("{\"price\":null}");
        when(client.price("AAPL", API_KEY)).thenReturn(Uni.createFrom().item(node));

        assertThrows(IllegalStateException.class,
                () -> adapter.fetchSpotPrice("AAPL").await().indefinitely());
    }

    @Test
    void givenFxResponse_whenFetchFxRate_thenReturnsRate() throws Exception {
        JsonNode node = MAPPER.readTree("{\"rate\":\"1.2345\"}");
        when(client.exchangeRate("EUR/USD", API_KEY)).thenReturn(Uni.createFrom().item(node));

        BigDecimal rate = adapter.fetchFxRate(Currency.EUR, Currency.USD).await().indefinitely();

        assertEquals(0, new BigDecimal("1.2345").compareTo(rate));
    }

    @Test
    void givenMissingFxRate_whenFetchFxRate_thenThrows() throws Exception {
        JsonNode node = MAPPER.readTree("{}");
        when(client.exchangeRate("EUR/USD", API_KEY)).thenReturn(Uni.createFrom().item(node));

        assertThrows(IllegalStateException.class,
                () -> adapter.fetchFxRate(Currency.EUR, Currency.USD).await().indefinitely());
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

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to).await().indefinitely();

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

        assertThrows(IllegalStateException.class,
                () -> adapter.fetchSpotPrice("AAPL").await().indefinitely());
    }

    @Test
    void givenNullFxRateNode_whenFetchFxRate_thenThrows() throws Exception {
        JsonNode node = MAPPER.readTree("{\"rate\":null}");
        when(client.exchangeRate("EUR/USD", API_KEY)).thenReturn(Uni.createFrom().item(node));

        assertThrows(IllegalStateException.class,
                () -> adapter.fetchFxRate(Currency.EUR, Currency.USD).await().indefinitely());
    }

    @Test
    void givenMissingValuesNode_whenFetchPriceHistory_thenReturnsEmptyList() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("{}");
        when(client.timeSeries("AAPL", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to).await().indefinitely();

        assertTrue(entries.isEmpty());
    }

    @Test
    void givenNonArrayValues_whenFetchPriceHistory_thenReturnsEmptyList() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("{\"values\":\"not-an-array\"}");
        when(client.timeSeries("AAPL", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to).await().indefinitely();

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

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to).await().indefinitely();

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
                .await().indefinitely();

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
                .await().indefinitely();

        assertTrue(entries.isEmpty());
    }

    @Test
    void givenEmptyValues_whenFetchPriceHistory_thenReturnsEmptyList() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        JsonNode node = MAPPER.readTree("{\"values\":[]}");
        when(client.timeSeries("AAPL", "1day", from.toString(), to.toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(node));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to).await().indefinitely();

        assertTrue(entries.isEmpty());
    }

    @Test
    void givenInvalidCurrencyMeta_whenFetchPriceHistory_thenDefaultsToUsd() throws Exception {
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

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to).await().indefinitely();

        assertEquals(Currency.USD, entries.getFirst().currency());
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
                .await().indefinitely();

        assertEquals(2, entries.size());
        assertEquals(Currency.EUR, entries.get(0).baseCurrency());
        assertEquals(Currency.USD, entries.get(0).quoteCurrency());
        assertEquals(from, entries.get(0).rateDate());
        assertEquals(0, new BigDecimal("1.10").compareTo(entries.get(0).rate()));
    }
}

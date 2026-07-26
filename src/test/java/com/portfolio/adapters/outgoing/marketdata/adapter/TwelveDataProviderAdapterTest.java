package com.portfolio.adapters.outgoing.marketdata.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.adapters.common.ReactiveRateLimiter;
import com.portfolio.adapters.outgoing.marketdata.client.TwelveDataClient;
import com.portfolio.adapters.outgoing.marketdata.client.TwelveDataExchangeRateResponse;
import com.portfolio.adapters.outgoing.marketdata.client.TwelveDataPriceResponse;
import com.portfolio.adapters.outgoing.marketdata.client.TwelveDataTimeSeriesResponse;
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
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
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
        adapter = new TwelveDataProviderAdapter(client, API_KEY, currencyResolver, permissiveLimiter());
    }

    private static ReactiveRateLimiter permissiveLimiter() {
        return new ReactiveRateLimiter("test", 60_000_000, Duration.ofMinutes(2));
    }

    private static TwelveDataPriceResponse price(String json) throws Exception {
        return MAPPER.readValue(json, TwelveDataPriceResponse.class);
    }

    private static TwelveDataExchangeRateResponse fx(String json) throws Exception {
        return MAPPER.readValue(json, TwelveDataExchangeRateResponse.class);
    }

    private static TwelveDataTimeSeriesResponse series(String json) throws Exception {
        return MAPPER.readValue(json, TwelveDataTimeSeriesResponse.class);
    }

    @Test
    void givenPriceResponse_whenFetchSpotPrice_thenReturnsPrice() throws Exception {
        when(client.price("AAPL", API_KEY))
                .thenReturn(Uni.createFrom().item(price("{\"price\":\"123.45\"}")));

        BigDecimal result = adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(0, new BigDecimal("123.45").compareTo(result));
    }

    @Test
    void givenMissingPrice_whenFetchSpotPrice_thenThrows() throws Exception {
        when(client.price("AAPL", API_KEY))
                .thenReturn(Uni.createFrom().item(price("{\"price\":null}")));

        adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    @Test
    void givenErrorStatusResponse_whenFetchSpotPrice_thenThrows() throws Exception {
        when(client.price("AAPL", API_KEY)).thenReturn(Uni.createFrom().item(
                price("{\"status\":\"error\",\"message\":\"bad symbol\"}")));

        adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ErrorResponse.class);
    }

    @Test
    void givenHttpClientError_whenFetchSpotPrice_thenThrowsTypedErrorForFallback() {
        when(client.price("AAPL", API_KEY)).thenReturn(Uni.createFrom().failure(
                new WebApplicationException(Response.status(401).build())));

        adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ErrorResponse.class);
    }

    @Test
    void givenTransportFailure_whenFetchSpotPrice_thenThrowsUnavailableForFallback() {
        when(client.price("AAPL", API_KEY)).thenReturn(Uni.createFrom().failure(
                new ProcessingException("timeout")));

        adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ProviderUnavailable.class);
    }

    @Test
    void givenUnexpectedMappingFailure_whenFetchSpotPrice_thenDoesNotHideBugAsProviderFailure() throws Exception {
        when(client.price("AAPL", API_KEY))
                .thenReturn(Uni.createFrom().item(price("{\"price\":\"not-a-number\"}")));

        adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(NumberFormatException.class);
    }

    @Test
    void givenRateLimitErrorCode_whenFetchSpotPrice_thenThrowsRateLimited() throws Exception {
        when(client.price("AAPL", API_KEY)).thenReturn(Uni.createFrom().item(
                price("{\"code\":429,\"status\":\"error\",\"message\":\"You have run out of API credits\"}")));

        MarketDataProviderError.RateLimited failure = (MarketDataProviderError.RateLimited)
                adapter.fetchSpotPrice("AAPL")
                        .subscribe().withSubscriber(UniAssertSubscriber.create())
                        .awaitFailure()
                        .assertFailedWith(MarketDataProviderError.RateLimited.class)
                        .getFailure();

        assertEquals(Duration.ofMinutes(1), failure.retryAfter());
    }

    @Test
    void givenSaturatedLimiter_whenFetchSpotPrice_thenThrowsRateLimitedWithoutCallingProvider() {
        // 1 credit/min with zero max-wait: first acquire consumes the immediate slot, the
        // second projects a 60s wait > 0s cap and is rejected before any client call.
        ReactiveRateLimiter tinyLimiter = new ReactiveRateLimiter("test", 1, Duration.ZERO);
        adapter = new TwelveDataProviderAdapter(client, API_KEY, currencyResolver, tinyLimiter);
        when(client.price("AAPL", API_KEY))
                .thenReturn(Uni.createFrom().item(new TwelveDataPriceResponse("123.45", null, null, null)));

        adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        MarketDataProviderError.RateLimited failure = (MarketDataProviderError.RateLimited)
                adapter.fetchSpotPrice("AAPL")
                        .subscribe().withSubscriber(UniAssertSubscriber.create())
                        .awaitFailure()
                        .assertFailedWith(MarketDataProviderError.RateLimited.class)
                        .getFailure();

        assertTrue(failure.retryAfter().compareTo(Duration.ZERO) > 0);
        Mockito.verify(client, Mockito.times(1)).price("AAPL", API_KEY);
    }

    @Test
    void givenNumericJsonRate_whenFetchFxRate_thenReturnsRate() throws Exception {
        // The API emits rate as a JSON number; Jackson coerces it into the String field.
        when(client.exchangeRate("EUR/USD", API_KEY))
                .thenReturn(Uni.createFrom().item(fx("{\"rate\":1.2345}")));

        BigDecimal rate = adapter.fetchFxRate(Currency.EUR, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(0, new BigDecimal("1.2345").compareTo(rate));
    }

    @Test
    void givenMissingFxRate_whenFetchFxRate_thenThrows() throws Exception {
        when(client.exchangeRate("EUR/USD", API_KEY))
                .thenReturn(Uni.createFrom().item(fx("{}")));

        adapter.fetchFxRate(Currency.EUR, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    @Test
    void givenErrorStatusResponse_whenFetchFxRate_thenThrows() throws Exception {
        when(client.exchangeRate("EUR/USD", API_KEY)).thenReturn(Uni.createFrom().item(
                fx("{\"status\":\"error\",\"message\":\"bad symbol\"}")));

        adapter.fetchFxRate(Currency.EUR, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ErrorResponse.class);
    }

    /**
     * Twelve Data's end_date is exclusive for the 1day interval, so the adapter must request the
     * day after the caller's inclusive {@code to}. Getting this wrong drops the newest bar of every
     * range, and MarketDataService then records the range as covered and never retries it.
     */
    @Test
    void givenInclusiveTo_whenFetchPriceHistory_thenRequestsDayAfterAsEndDate() throws Exception {
        LocalDate from = LocalDate.of(2026, 7, 22);
        LocalDate to = LocalDate.of(2026, 7, 24);
        when(client.timeSeries("AAPL", "1day", "2026-07-22", "2026-07-25", API_KEY))
                .thenReturn(Uni.createFrom().item(series("""
                        {
                          "meta": {"currency": "USD", "symbol": "AAPL"},
                          "values": [
                            {"datetime": "2026-07-24", "close": "333.019989", "volume": "1000"}
                          ],
                          "status": "ok"
                        }
                        """)));

        List<PriceHistoryEntry> entries = adapter.fetchPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(1, entries.size());
        assertEquals(to, entries.getFirst().priceDate());
        verify(client).timeSeries("AAPL", "1day", "2026-07-22", "2026-07-25", API_KEY);
    }

    @Test
    void givenTimeSeries_whenFetchPriceHistory_thenParsesEntries() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);
        TwelveDataTimeSeriesResponse response = series("""
                {
                  "meta": {"currency": "USD", "symbol": "AAPL"},
                  "values": [
                    {"datetime": "2024-01-01", "close": "100.5", "volume": "1000"},
                    {"datetime": "2024-01-02", "close": "101.0", "volume": "1000"}
                  ],
                  "status": "ok"
                }
                """);
        when(client.timeSeries("AAPL", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(response));

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
        when(client.price("AAPL", API_KEY))
                .thenReturn(Uni.createFrom().item(price("{}")));

        adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    @Test
    void givenNullFxRateNode_whenFetchFxRate_thenThrows() throws Exception {
        when(client.exchangeRate("EUR/USD", API_KEY))
                .thenReturn(Uni.createFrom().item(fx("{\"rate\":null}")));

        adapter.fetchFxRate(Currency.EUR, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    @Test
    void givenErrorStatusResponse_whenFetchPriceHistory_thenThrows() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        when(client.timeSeries("AAPL", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(
                        series("{\"status\":\"error\",\"message\":\"bad symbol\"}")));

        adapter.fetchPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ErrorResponse.class);
    }

    @Test
    void givenRateLimitErrorCode_whenFetchPriceHistory_thenThrowsRateLimited() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        when(client.timeSeries("AAPL", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(series(
                        "{\"code\":429,\"status\":\"error\",\"message\":\"You have run out of API credits\"}")));

        adapter.fetchPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.RateLimited.class);
    }

    @Test
    void givenErrorStatusResponse_whenFetchFxHistory_thenThrows() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        when(client.timeSeries("EUR/USD", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(
                        series("{\"status\":\"error\",\"message\":\"bad symbol\"}")));

        adapter.fetchFxHistory(Currency.EUR, Currency.USD, from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ErrorResponse.class);
    }

    @Test
    void givenMissingValuesNode_whenFetchPriceHistory_thenReturnsEmptyList() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        when(client.timeSeries("AAPL", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(series("{}")));

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
        TwelveDataTimeSeriesResponse response = series("""
                {
                  "values": [{"datetime": "2024-01-01", "close": "50"}]
                }
                """);
        when(client.timeSeries("AAPL", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(response));

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
        when(client.timeSeries("EUR/USD", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(series("{}")));

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
        when(client.timeSeries("AAPL", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(series("{\"values\":[]}")));

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
        TwelveDataTimeSeriesResponse response = series("""
                {
                  "meta": {"currency": "NOT_A_CURRENCY"},
                  "values": [{"datetime": "2024-01-01", "close": "50"}]
                }
                """);
        when(client.timeSeries("AAPL", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(response));

        adapter.fetchPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.UnsupportedCurrency.class);
    }

    @Test
    void givenGbxCurrencyMeta_whenFetchPriceHistory_thenScalesToGbp() throws Exception {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 1);
        TwelveDataTimeSeriesResponse response = series("""
                {
                  "meta": {"currency": "GBX"},
                  "values": [{"datetime": "2024-01-01", "close": "12345"}]
                }
                """);
        when(client.timeSeries("LLOY", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(response));

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
        TwelveDataTimeSeriesResponse response = series("""
                {
                  "meta": {"currency": "GBp"},
                  "values": [{"datetime": "2024-01-01", "close": "500"}]
                }
                """);
        when(client.timeSeries("LLOY", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(response));

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
        TwelveDataTimeSeriesResponse response = series("""
                {
                  "meta": {"currency": "GBP"},
                  "values": [{"datetime": "2024-01-01", "close": "5.00"}]
                }
                """);
        when(client.timeSeries("BP", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(response));

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
        TwelveDataTimeSeriesResponse response = series("""
                {
                  "values": [
                    {"datetime": "2024-01-01", "close": "1.10"},
                    {"datetime": "2024-01-02", "close": "1.11"}
                  ]
                }
                """);
        when(client.timeSeries("EUR/USD", "1day", from.toString(), to.plusDays(1).toString(), API_KEY))
                .thenReturn(Uni.createFrom().item(response));

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

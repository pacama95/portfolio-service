package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.portfolio.adapters.common.ReactiveRateLimiter;
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
import org.eclipse.microprofile.config.Config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EodhdProviderAdapterTest {

    private static final String API_KEY = "api-key";

    private final EodhdClient client = Mockito.mock(EodhdClient.class);
    private final EodhdSymbolResolver resolver = Mockito.mock(EodhdSymbolResolver.class);
    private final CurrencyResolver currencyResolver = new CurrencyResolver(Map.of("GBX", "GBP:0.01"));
    private EodhdProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = adapter(Map.of("LSE", "GBX"));
    }

    @Test
    void givenResolvedEquity_whenFetchingSpot_thenUsesQualifiedSymbolAndReturnsPortValue() {
        when(resolver.resolve("AAPL")).thenReturn(Uni.createFrom().item(
                new EodhdResolvedSymbol("AAPL", "AAPL.US", "US", "USD")));
        when(client.realTime("AAPL.US", API_KEY, "json"))
                .thenReturn(Uni.createFrom().item(new EodhdRealTimeResponse("AAPL.US", 1L, new BigDecimal("189.25"))));

        BigDecimal result = await(adapter.fetchSpotPrice("AAPL"));

        assertEquals(new BigDecimal("189.25"), result);
        verify(client).realTime("AAPL.US", API_KEY, "json");
    }

    @Test
    void givenMinorUnitExchangeOverride_whenFetchingSpot_thenNormalizesToMajorCurrency() {
        when(resolver.resolve("BARC")).thenReturn(Uni.createFrom().item(
                new EodhdResolvedSymbol("BARC", "BARC.LSE", "LSE", "GBP")));
        when(client.realTime("BARC.LSE", API_KEY, "json"))
                .thenReturn(Uni.createFrom().item(new EodhdRealTimeResponse(
                        "BARC.LSE", 1L, new BigDecimal("123.45"))));

        assertEquals(new BigDecimal("1.2345"), await(adapter.fetchSpotPrice("BARC")));
    }

    @Test
    void givenConfiguredCurrencyOverrides_whenConstructingInjectedAdapter_thenExtractsOnlyPresentEodhdValues() {
        Config config = Mockito.mock(Config.class);
        String prefix = "application.market-data.eodhd.exchange-price-currency-overrides.";
        when(config.getPropertyNames()).thenReturn(List.of(
                "unrelated.property", prefix + "lse", prefix + "missing"));
        when(config.getOptionalValue(prefix + "lse", String.class)).thenReturn(Optional.of("GBX"));
        when(config.getOptionalValue(prefix + "missing", String.class)).thenReturn(Optional.empty());
        adapter = new EodhdProviderAdapter(
                client,
                resolver,
                currencyResolver,
                API_KEY,
                new ReactiveRateLimiter("test", 60_000, Duration.ofMinutes(1)),
                config);
        when(resolver.resolve("BARC")).thenReturn(Uni.createFrom().item(
                new EodhdResolvedSymbol("BARC", "BARC.LSE", "LSE", "GBP")));
        when(client.realTime("BARC.LSE", API_KEY, "json"))
                .thenReturn(Uni.createFrom().item(new EodhdRealTimeResponse(
                        "BARC.LSE", 1L, new BigDecimal("123.45"))));

        assertEquals(new BigDecimal("1.2345"), await(adapter.fetchSpotPrice("BARC")));
    }

    @Test
    void givenEodBars_whenFetchingPriceHistory_thenKeepsInclusiveDatesAndCanonicalSymbol() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);
        when(resolver.resolve("AAPL")).thenReturn(Uni.createFrom().item(
                new EodhdResolvedSymbol("AAPL", "AAPL.US", "US", "USD")));
        when(client.eod("AAPL.US", API_KEY, "json", "2024-01-01", "2024-01-02", "d", "a"))
                .thenReturn(Uni.createFrom().item(List.of(
                        new EodhdEodBarResponse("2024-01-02", new BigDecimal("101"), null),
                        new EodhdEodBarResponse("2024-01-01", new BigDecimal("100"), new BigDecimal("99")))));

        List<PriceHistoryEntry> result = await(adapter.fetchPriceHistory("AAPL", from, to));

        assertEquals(List.of(from, to), result.stream().map(PriceHistoryEntry::priceDate).toList());
        assertEquals("AAPL", result.getFirst().symbol());
        assertEquals(new BigDecimal("99"), result.getFirst().adjustedClosePrice());
        assertEquals(new BigDecimal("101"), result.get(1).adjustedClosePrice());
        verify(client).eod("AAPL.US", API_KEY, "json", "2024-01-01", "2024-01-02", "d", "a");
    }

    @Test
    void givenFxRequest_whenFetchingSpotAndHistory_thenBuildsForexSymbolOnlyInsideAdapter() {
        when(client.realTime("EURUSD.FOREX", API_KEY, "json"))
                .thenReturn(Uni.createFrom().item(new EodhdRealTimeResponse(
                        "EURUSD.FOREX", 1L, new BigDecimal("1.08"))));
        when(client.eod("EURUSD.FOREX", API_KEY, "json", "2024-01-01", "2024-01-02", "d", "a"))
                .thenReturn(Uni.createFrom().item(List.of(
                        new EodhdEodBarResponse("2024-01-01", new BigDecimal("1.07"), null))));

        assertEquals(new BigDecimal("1.08"), await(adapter.fetchFxRate(Currency.EUR, Currency.USD)));
        List<FxRateEntry> history = await(adapter.fetchFxHistory(
                Currency.EUR, Currency.USD, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2)));

        assertEquals(new BigDecimal("1.07"), history.getFirst().rate());
        verify(client).realTime("EURUSD.FOREX", API_KEY, "json");
        verify(client).eod("EURUSD.FOREX", API_KEY, "json", "2024-01-01", "2024-01-02", "d", "a");
    }

    @Test
    void givenLegitimateEmptyHistory_whenFetching_thenReturnsSuccessfulEmptyList() {
        when(resolver.resolve("AAPL")).thenReturn(Uni.createFrom().item(
                new EodhdResolvedSymbol("AAPL", "AAPL.US", "US", "USD")));
        when(client.eod("AAPL.US", API_KEY, "json", "2024-01-01", "2024-01-02", "d", "a"))
                .thenReturn(Uni.createFrom().item(List.of()));

        assertEquals(List.of(), await(adapter.fetchPriceHistory(
                "AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2))));
    }

    @Test
    void givenSubscriptionWarning_whenFetchingHistory_thenRejectsTruncatedRangeForFallback() {
        when(resolver.resolve("AAPL")).thenReturn(Uni.createFrom().item(
                new EodhdResolvedSymbol("AAPL", "AAPL.US", "US", "USD")));
        when(client.eod("AAPL.US", API_KEY, "json", "2024-01-01", "2026-07-24", "d", "a"))
                .thenReturn(Uni.createFrom().item(List.of(
                        new EodhdEodBarResponse(
                                "2025-07-28", new BigDecimal("214.05"), new BigDecimal("213.2042"), null),
                        new EodhdEodBarResponse(
                                "2026-07-24",
                                new BigDecimal("333.02"),
                                new BigDecimal("333.02"),
                                "Data is limited by one year as you have free subscription"))));

        adapter.fetchPriceHistory(
                        "AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2026, 7, 24))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ErrorResponse.class);
    }

    @Test
    void givenWarningOnlyResponse_whenFetchingFxHistory_thenReturnsTypedProviderFailure() {
        when(client.eod("EURUSD.FOREX", API_KEY, "json", "2024-01-01", "2024-01-02", "d", "a"))
                .thenReturn(Uni.createFrom().item(List.of(new EodhdEodBarResponse(
                        null, null, null, "Data is limited by one year as you have free subscription"))));

        adapter.fetchFxHistory(
                        Currency.EUR,
                        Currency.USD,
                        LocalDate.of(2024, 1, 1),
                        LocalDate.of(2024, 1, 2))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ErrorResponse.class);
    }

    @Test
    void givenMissingRequiredClose_whenFetching_thenFailsTyped() {
        when(resolver.resolve("AAPL")).thenReturn(Uni.createFrom().item(
                new EodhdResolvedSymbol("AAPL", "AAPL.US", "US", "USD")));
        when(client.realTime("AAPL.US", API_KEY, "json"))
                .thenReturn(Uni.createFrom().item(new EodhdRealTimeResponse("AAPL.US", 1L, null)));

        adapter.fetchSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    @Test
    void givenNullOrMissingPriceHistoryFields_whenFetching_thenFailsTyped() {
        when(resolver.resolve("AAPL")).thenReturn(Uni.createFrom().item(
                new EodhdResolvedSymbol("AAPL", "AAPL.US", "US", "USD")));
        when(client.eod("AAPL.US", API_KEY, "json", "2024-01-01", "2024-01-01", "d", "a"))
                .thenReturn(Uni.createFrom().nullItem())
                .thenReturn(Uni.createFrom().item(List.of(
                        new EodhdEodBarResponse("2024-01-01", null, null))));

        assertMissing(adapter.fetchPriceHistory(
                "AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)));
        assertMissing(adapter.fetchPriceHistory(
                "AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)));
    }

    @Test
    void givenNullInvalidOrBlankBarDate_whenFetchingHistory_thenFailsTyped() {
        when(resolver.resolve("AAPL")).thenReturn(Uni.createFrom().item(
                new EodhdResolvedSymbol("AAPL", "AAPL.US", "US", "USD")));
        when(client.eod("AAPL.US", API_KEY, "json", "2024-01-01", "2024-01-01", "d", "a"))
                .thenReturn(Uni.createFrom().item(java.util.Arrays.asList(
                        (EodhdEodBarResponse) null)))
                .thenReturn(Uni.createFrom().item(List.of(
                        new EodhdEodBarResponse("bad-date", BigDecimal.ONE, null))))
                .thenReturn(Uni.createFrom().item(List.of(
                        new EodhdEodBarResponse(" ", BigDecimal.ONE, null))));

        assertMissing(adapter.fetchPriceHistory(
                "AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)));
        assertMissing(adapter.fetchPriceHistory(
                "AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)));
        assertMissing(adapter.fetchPriceHistory(
                "AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)));
    }

    @Test
    void givenNullOrMissingFxHistoryFields_whenFetching_thenFailsTyped() {
        when(client.eod("EURUSD.FOREX", API_KEY, "json", "2024-01-01", "2024-01-01", "d", "a"))
                .thenReturn(Uni.createFrom().nullItem())
                .thenReturn(Uni.createFrom().item(List.of(
                        new EodhdEodBarResponse("2024-01-01", null, null))));

        assertMissing(adapter.fetchFxHistory(
                Currency.EUR, Currency.USD, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)));
        assertMissing(adapter.fetchFxHistory(
                Currency.EUR, Currency.USD, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)));
    }

    @Test
    void givenMissingOrUnsupportedEquityCurrency_whenFetching_thenFailsTyped() {
        when(resolver.resolve("MISSING")).thenReturn(Uni.createFrom().item(
                new EodhdResolvedSymbol("MISSING", "MISSING.US", "US", null)));
        when(resolver.resolve("UNKNOWN")).thenReturn(Uni.createFrom().item(
                new EodhdResolvedSymbol("UNKNOWN", "UNKNOWN.US", "US", "ZZZ")));

        assertMissing(adapter.fetchSpotPrice("MISSING"));
        adapter.fetchSpotPrice("UNKNOWN")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.UnsupportedCurrency.class);
    }

    private void assertMissing(Uni<?> result) {
        result.subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    private EodhdProviderAdapter adapter(Map<String, String> overrides) {
        return new EodhdProviderAdapter(
                client,
                resolver,
                currencyResolver,
                API_KEY,
                new ReactiveRateLimiter("test", 60_000, Duration.ofMinutes(1)),
                overrides);
    }

    private <T> T await(Uni<T> result) {
        return result.subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().getItem();
    }
}

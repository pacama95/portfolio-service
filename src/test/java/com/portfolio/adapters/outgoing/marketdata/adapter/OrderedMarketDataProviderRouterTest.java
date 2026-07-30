package com.portfolio.adapters.outgoing.marketdata.adapter;

import com.portfolio.core.model.Currency;
import com.portfolio.core.model.MarketDataProviderResult;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import com.portfolio.core.ports.outgoing.MarketDataProviderPort;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.eclipse.microprofile.config.Config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderedMarketDataProviderRouterTest {

    private final MarketDataProviderPort twelveData = Mockito.mock(MarketDataProviderPort.class);
    private final MarketDataProviderPort eodhd = Mockito.mock(MarketDataProviderPort.class);

    @Test
    void givenPrimarySuccess_whenRouting_thenDoesNotCallFallbackAndReturnsProvenance() {
        when(twelveData.fetchSpotPrice("AAPL")).thenReturn(Uni.createFrom().item(new BigDecimal("10")));
        OrderedMarketDataProviderRouter router = router("twelvedata,eodhd");

        MarketDataProviderResult<BigDecimal> result = await(router.fetchSpotPriceWithProvider("AAPL"));

        assertEquals("twelvedata", result.providerId());
        assertEquals(new BigDecimal("10"), result.value());
        verify(eodhd, never()).fetchSpotPrice(any());
    }

    @Test
    void givenTypedPrimaryFailure_whenRouting_thenUsesEodhdFallback() {
        when(twelveData.fetchFxRate(Currency.EUR, Currency.USD)).thenReturn(Uni.createFrom().failure(
                new MarketDataProviderError.ProviderUnavailable("EUR/USD")));
        when(eodhd.fetchFxRate(Currency.EUR, Currency.USD))
                .thenReturn(Uni.createFrom().item(new BigDecimal("1.08")));
        OrderedMarketDataProviderRouter router = router("twelvedata,eodhd");

        MarketDataProviderResult<BigDecimal> result = await(
                router.fetchFxRateWithProvider(Currency.EUR, Currency.USD));

        assertEquals("eodhd", result.providerId());
        assertEquals(new BigDecimal("1.08"), result.value());
    }

    @Test
    void givenUnexpectedPrimaryFailure_whenRouting_thenStopsImmediately() {
        when(twelveData.fetchSpotPrice("AAPL"))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("bug")));
        OrderedMarketDataProviderRouter router = router("twelvedata,eodhd");

        router.fetchSpotPriceWithProvider("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(IllegalStateException.class);

        verify(eodhd, never()).fetchSpotPrice(any());
    }

    @Test
    void givenSuccessfulEmptyHistory_whenRouting_thenDoesNotFallback() {
        LocalDate date = LocalDate.of(2024, 1, 1);
        when(twelveData.fetchPriceHistory("AAPL", date, date))
                .thenReturn(Uni.createFrom().item(List.of()));
        OrderedMarketDataProviderRouter router = router("twelvedata,eodhd");

        MarketDataProviderResult<List<PriceHistoryEntry>> result = await(
                router.fetchPriceHistoryWithProvider("AAPL", date, date));

        assertEquals(List.of(), result.value());
        assertEquals("twelvedata", result.providerId());
        verify(eodhd, never()).fetchPriceHistory(any(), any(), any());
    }

    @Test
    void givenEveryProviderRateLimited_whenRouting_thenReturnsEarliestRetry() {
        when(twelveData.fetchSpotPrice("AAPL")).thenReturn(Uni.createFrom().failure(
                new MarketDataProviderError.RateLimited("AAPL", Duration.ofSeconds(30))));
        when(eodhd.fetchSpotPrice("AAPL")).thenReturn(Uni.createFrom().failure(
                new MarketDataProviderError.RateLimited("AAPL", Duration.ofSeconds(10))));
        OrderedMarketDataProviderRouter router = router("twelvedata,eodhd");

        MarketDataProviderError.RateLimited failure = (MarketDataProviderError.RateLimited)
                router.fetchSpotPriceWithProvider("AAPL")
                        .subscribe().withSubscriber(UniAssertSubscriber.create())
                        .awaitFailure()
                        .assertFailedWith(MarketDataProviderError.RateLimited.class)
                        .getFailure();

        assertEquals(Duration.ofSeconds(10), failure.retryAfter());
    }

    @Test
    void givenMixedTypedFailures_whenRoutingExhausted_thenReturnsAggregate() {
        when(twelveData.fetchSpotPrice("AAPL")).thenReturn(Uni.createFrom().failure(
                new MarketDataProviderError.MissingData("AAPL", "price")));
        when(eodhd.fetchSpotPrice("AAPL")).thenReturn(Uni.createFrom().failure(
                new MarketDataProviderError.ProviderUnavailable("AAPL")));
        OrderedMarketDataProviderRouter router = router("twelvedata,eodhd");

        MarketDataProviderError.AllProvidersFailed failure = (MarketDataProviderError.AllProvidersFailed)
                router.fetchSpotPriceWithProvider("AAPL")
                        .subscribe().withSubscriber(UniAssertSubscriber.create())
                        .awaitFailure()
                        .assertFailedWith(MarketDataProviderError.AllProvidersFailed.class)
                        .getFailure();

        assertEquals(2, failure.getSuppressed().length);
    }

    @Test
    void givenEodhdFirst_whenRouting_thenHonorsConfiguredOrder() {
        when(eodhd.fetchSpotPrice("AAPL")).thenReturn(Uni.createFrom().item(new BigDecimal("11")));

        assertEquals("eodhd", await(router("eodhd,twelvedata")
                .fetchSpotPriceWithProvider("AAPL")).providerId());

        verify(twelveData, never()).fetchSpotPrice(any());
    }

    @Test
    void givenInvalidProviderConfiguration_whenConstructing_thenFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> router(null));
        assertThrows(IllegalArgumentException.class, () -> router(""));
        assertThrows(IllegalArgumentException.class, () -> router("twelvedata,"));
        assertThrows(IllegalArgumentException.class, () -> router("eodhd,eodhd"));
        assertThrows(IllegalArgumentException.class, () -> router("unknown"));
    }

    @Test
    void givenSingleConfiguredProviderFailure_whenRouting_thenPreservesTypedFailure() {
        MarketDataProviderError.MissingData failure = new MarketDataProviderError.MissingData("AAPL", "close");
        when(eodhd.fetchSpotPrice("AAPL")).thenReturn(Uni.createFrom().failure(failure));

        Throwable result = router("eodhd").fetchSpotPriceWithProvider("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure().getFailure();

        assertEquals(failure, result);
    }

    @Test
    void givenFxAndHistoryOperations_whenRouting_thenReportTheProviderThatAnswered() {
        LocalDate date = LocalDate.of(2024, 1, 1);
        when(twelveData.fetchFxRate(Currency.EUR, Currency.USD))
                .thenReturn(Uni.createFrom().item(new BigDecimal("1.1")));
        when(twelveData.fetchPriceHistory("AAPL", date, date))
                .thenReturn(Uni.createFrom().item(List.of()));
        when(twelveData.fetchFxHistory(Currency.EUR, Currency.USD, date, date))
                .thenReturn(Uni.createFrom().item(List.of()));
        OrderedMarketDataProviderRouter router = router("twelvedata");

        assertEquals(new BigDecimal("1.1"),
                await(router.fetchFxRateWithProvider(Currency.EUR, Currency.USD)).value());
        assertEquals("twelvedata",
                await(router.fetchPriceHistoryWithProvider("AAPL", date, date)).providerId());
        assertEquals("twelvedata",
                await(router.fetchFxHistoryWithProvider(Currency.EUR, Currency.USD, date, date)).providerId());
    }

    @Test
    void givenMissingConfiguredCredential_whenConstructingCdiRouter_thenFailsFast() {
        Config config = Mockito.mock(Config.class);
        when(config.getOptionalValue("application.market-data.twelve-data.api-key", String.class))
                .thenReturn(java.util.Optional.of("not-configured"));

        assertThrows(IllegalArgumentException.class, () -> new OrderedMarketDataProviderRouter(
                twelveData, eodhd, "twelvedata", config));
    }

    @Test
    void givenAbsentConfiguredCredential_whenConstructingCdiRouter_thenFailsFast() {
        Config config = Mockito.mock(Config.class);
        when(config.getOptionalValue("application.market-data.eodhd.api-key", String.class))
                .thenReturn(java.util.Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> new OrderedMarketDataProviderRouter(
                twelveData, eodhd, "eodhd", config));
    }

    @Test
    void givenConfiguredCredentials_whenConstructingCdiRouter_thenAcceptsEveryListedProvider() {
        Config config = Mockito.mock(Config.class);
        when(config.getOptionalValue("application.market-data.twelve-data.api-key", String.class))
                .thenReturn(java.util.Optional.of("td-key"));
        when(config.getOptionalValue("application.market-data.eodhd.api-key", String.class))
                .thenReturn(java.util.Optional.of("eod-key"));

        new OrderedMarketDataProviderRouter(twelveData, eodhd, "twelvedata,eodhd", config);
    }

    private OrderedMarketDataProviderRouter router(String providers) {
        return new OrderedMarketDataProviderRouter(twelveData, eodhd, providers);
    }

    private <T> T await(Uni<T> result) {
        return result.subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().getItem();
    }
}

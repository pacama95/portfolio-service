package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.portfolio.adapters.common.ReactiveRateLimiter;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EodhdExchangeCatalogTest {

    private static final String API_KEY = "api-key";

    private final EodhdClient client = Mockito.mock(EodhdClient.class);
    private final EodhdReferenceDataStore store = Mockito.mock(EodhdReferenceDataStore.class);
    private EodhdExchangeCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new EodhdExchangeCatalog(
                client,
                store,
                API_KEY,
                new ReactiveRateLimiter("test", 60_000, Duration.ofMinutes(1)),
                Duration.ofHours(24));
    }

    @Test
    void givenFreshPersistedCatalog_whenLoaded_thenDoesNotCallEodhd() {
        List<EodhdExchange> persisted = List.of(exchange("US", OffsetDateTime.now()));
        when(store.findExchanges()).thenReturn(Uni.createFrom().item(persisted));
        when(store.findLatestExchangeFetchTime())
                .thenReturn(Uni.createFrom().item(Optional.of(OffsetDateTime.now())));

        List<EodhdExchange> result = await(catalog.exchanges());

        assertEquals(persisted, result);
        verify(client, never()).exchanges(any(), any());
    }

    @Test
    void givenStaleCatalog_whenRefreshSucceeds_thenPersistsAtomicSnapshot() {
        when(store.findExchanges()).thenReturn(Uni.createFrom().item(List.of(exchange(
                "OLD", OffsetDateTime.now().minusDays(2)))));
        when(store.findLatestExchangeFetchTime())
                .thenReturn(Uni.createFrom().item(Optional.of(OffsetDateTime.now().minusDays(2))));
        when(client.exchanges(API_KEY, "json")).thenReturn(Uni.createFrom().item(List.of(
                new EodhdExchangeResponse("USA Stocks", "US", "XNAS,XNYS", "USA", "USD", "US", "USA"))));
        when(store.replaceExchanges(any(), any())).thenReturn(Uni.createFrom().voidItem());

        List<EodhdExchange> result = await(catalog.exchanges());

        assertEquals(1, result.size());
        assertEquals("US", result.getFirst().code());
        assertEquals("XNAS,XNYS", result.getFirst().operatingMic());
        ArgumentCaptor<List<EodhdExchange>> snapshot = ArgumentCaptor.forClass(List.class);
        verify(store).replaceExchanges(snapshot.capture(), any());
        assertEquals(result, snapshot.getValue());
    }

    @Test
    void givenPersistedCatalogWithoutFetchTimestamp_whenLoaded_thenRefreshesIt() {
        when(store.findExchanges()).thenReturn(Uni.createFrom().item(List.of(exchange(
                "OLD", OffsetDateTime.now().minusDays(2)))));
        when(store.findLatestExchangeFetchTime()).thenReturn(Uni.createFrom().item(Optional.empty()));
        when(client.exchanges(API_KEY, "json")).thenReturn(Uni.createFrom().item(List.of(
                new EodhdExchangeResponse("USA Stocks", "US", " ", null, "USD", "", "USA"))));
        when(store.replaceExchanges(any(), any())).thenReturn(Uni.createFrom().voidItem());

        EodhdExchange result = await(catalog.exchanges()).getFirst();

        assertEquals("US", result.code());
        assertEquals(null, result.operatingMic());
        assertEquals(null, result.country());
        assertEquals(null, result.countryIso2());
    }

    @Test
    void givenStaleCatalog_whenRefreshIsEmpty_thenKeepsLastGoodSnapshot() {
        List<EodhdExchange> stale = List.of(exchange("US", OffsetDateTime.now().minusDays(2)));
        when(store.findExchanges()).thenReturn(Uni.createFrom().item(stale));
        when(store.findLatestExchangeFetchTime())
                .thenReturn(Uni.createFrom().item(Optional.of(OffsetDateTime.now().minusDays(2))));
        when(client.exchanges(API_KEY, "json")).thenReturn(Uni.createFrom().item(List.of()));

        assertEquals(stale, await(catalog.exchanges()));
        verify(store, never()).replaceExchanges(any(), any());
    }

    @Test
    void givenNoPersistedCatalog_whenRefreshIsEmpty_thenFailsTyped() {
        when(store.findExchanges()).thenReturn(Uni.createFrom().item(List.of()));
        when(store.findLatestExchangeFetchTime()).thenReturn(Uni.createFrom().item(Optional.empty()));
        when(client.exchanges(API_KEY, "json")).thenReturn(Uni.createFrom().item(List.of()));

        catalog.exchanges()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    @Test
    void givenConcurrentStaleReads_whenRefreshing_thenSharesOneProviderRequest() {
        when(store.findExchanges()).thenReturn(Uni.createFrom().item(List.of()));
        when(store.findLatestExchangeFetchTime()).thenReturn(Uni.createFrom().item(Optional.empty()));
        when(store.replaceExchanges(any(), any())).thenReturn(Uni.createFrom().voidItem());
        AtomicReference<UniEmitter<? super List<EodhdExchangeResponse>>> upstream = new AtomicReference<>();
        when(client.exchanges(API_KEY, "json")).thenReturn(
                Uni.createFrom().<List<EodhdExchangeResponse>>emitter(upstream::set));

        UniAssertSubscriber<List<EodhdExchange>> first = catalog.exchanges()
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        UniAssertSubscriber<List<EodhdExchange>> second = catalog.exchanges()
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        upstream.get().complete(List.of(
                new EodhdExchangeResponse("USA Stocks", "US", "XNAS", "USA", "USD", "US", "USA")));

        assertEquals(1, first.awaitItem().getItem().size());
        assertEquals(1, second.awaitItem().getItem().size());
        verify(client).exchanges(API_KEY, "json");
    }

    @Test
    void givenExchangeWithoutCodeOrName_whenRefreshingWithoutStaleData_thenFailsTyped() {
        when(store.findExchanges()).thenReturn(Uni.createFrom().item(List.of()));
        when(store.findLatestExchangeFetchTime()).thenReturn(Uni.createFrom().item(Optional.empty()));
        when(client.exchanges(API_KEY, "json"))
                .thenReturn(Uni.createFrom().item(List.of(
                        new EodhdExchangeResponse("USA", null, null, null, null, null, null))))
                .thenReturn(Uni.createFrom().item(List.of(
                        new EodhdExchangeResponse(" ", "US", null, null, null, null, null))));

        assertCatalogMissing(catalog.exchanges());
        assertCatalogMissing(catalog.exchanges());
    }

    @Test
    void givenUnexpectedRefreshFailureWithoutStaleData_thenReturnsTypedUnavailable() {
        when(store.findExchanges()).thenReturn(Uni.createFrom().item(List.of()));
        when(store.findLatestExchangeFetchTime()).thenReturn(Uni.createFrom().item(Optional.empty()));
        when(client.exchanges(API_KEY, "json"))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("unexpected")));

        catalog.exchanges()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.ProviderUnavailable.class);
    }

    @Test
    void givenSaturatedCatalogLimiter_whenRefreshing_thenFailsBeforeProviderCall() {
        ReactiveRateLimiter limiter = new ReactiveRateLimiter("test", 1, Duration.ZERO);
        limiter.acquire().subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem();
        catalog = new EodhdExchangeCatalog(client, store, API_KEY, limiter, Duration.ofHours(24));
        when(store.findExchanges()).thenReturn(Uni.createFrom().item(List.of()));
        when(store.findLatestExchangeFetchTime()).thenReturn(Uni.createFrom().item(Optional.empty()));

        catalog.exchanges()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.RateLimited.class);

        verify(client, never()).exchanges(any(), any());
    }

    private EodhdExchange exchange(String code, OffsetDateTime fetchedAt) {
        return new EodhdExchange(code, code + " Exchange", null, null, "USD", null, null, true, fetchedAt);
    }

    private List<EodhdExchange> await(Uni<List<EodhdExchange>> result) {
        return result.subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().getItem();
    }

    private void assertCatalogMissing(Uni<List<EodhdExchange>> result) {
        result.subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }
}

package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.portfolio.adapters.common.ReactiveRateLimiter;
import com.portfolio.core.application.service.CurrencyResolver;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.InstrumentListing;
import com.portfolio.core.ports.outgoing.InstrumentListingPort;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EodhdSymbolResolverTest {

    private static final String API_KEY = "api-key";

    private final EodhdClient client = Mockito.mock(EodhdClient.class);
    private final EodhdExchangeCatalog catalog = Mockito.mock(EodhdExchangeCatalog.class);
    private final EodhdReferenceDataStore store = Mockito.mock(EodhdReferenceDataStore.class);
    private final InstrumentListingPort listings = Mockito.mock(InstrumentListingPort.class);
    private final CurrencyResolver currencyResolver = new CurrencyResolver(Map.of("GBX", "GBP:0.01"));
    private EodhdSymbolResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = resolver(Map.of());
        when(store.findSymbolMapping(any())).thenReturn(Uni.createFrom().item(Optional.empty()));
        when(store.upsertSymbolMapping(any())).thenReturn(Uni.createFrom().voidItem());
    }

    @Test
    void givenNasdaqTransactionMetadata_whenSearchReturnsUsListing_thenResolvesQualifiedSymbol() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("AAPL")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("AAPL", "NASDAQ", "US", Currency.USD))));
        when(client.search("AAPL", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("AAPL", "US", "USA", "USD"))));

        EodhdResolvedSymbol result = await(resolver.resolve("aapl"));

        assertEquals(new EodhdResolvedSymbol("AAPL", "AAPL.US", "US", "USD"), result);
        ArgumentCaptor<EodhdSymbolMapping> persisted = ArgumentCaptor.forClass(EodhdSymbolMapping.class);
        verify(store).upsertSymbolMapping(persisted.capture());
        assertEquals(EodhdResolutionSource.TRANSACTION_METADATA, persisted.getValue().resolutionSource());
    }

    @Test
    void givenOperatingMicMetadata_whenResolving_thenUsesKnownExchangeAsSearchFilter() {
        EodhdExchange lse = new EodhdExchange(
                "LSE", "London Stock Exchange", "XLON", "UK", "GBX", "GB", "GBR", true,
                OffsetDateTime.now());
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(lse)));
        when(listings.findBySymbol("BARC")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("BARC", "XLON", "GB", Currency.GBP))));
        when(client.search("BARC", API_KEY, "json", "LSE")).thenReturn(Uni.createFrom().item(List.of(
                search("BARC", "LSE", "UK", "GBX"))));

        assertEquals("BARC.LSE", await(resolver.resolve("BARC")).providerSymbol());
        verify(client).search("BARC", API_KEY, "json", "LSE");
    }

    @Test
    void givenNoMetadataAndAmbiguousExactResults_whenResolving_thenFailsExplicitly() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(
                usExchange(),
                new EodhdExchange("LSE", "London", "XLON", "UK", "GBP", "GB", "GBR", true,
                        OffsetDateTime.now()))));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of()));
        when(client.search("ABC", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("ABC", "US", "USA", "USD"),
                search("ABC", "LSE", "UK", "GBP"))));

        resolver.resolve("ABC")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.SymbolResolution.class);

        verify(store, never()).upsertSymbolMapping(any());
    }

    @Test
    void givenConflictingTransactionListings_whenResolving_thenDoesNotGuessOrSearch() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("ABC", "NASDAQ", "US", Currency.USD),
                new InstrumentListing("ABC", "LSE", "GB", Currency.GBP))));

        resolver.resolve("ABC")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.SymbolResolution.class);

        verify(client, never()).search(any(), any(), any(), any());
    }

    @Test
    void givenConfiguredOverrideForDottedTicker_whenResolving_thenNeverParsesInputDotAsExchange() {
        resolver = resolver(Map.of("BRK.B", "BRK.B.US"));
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("BRK.B")).thenReturn(Uni.createFrom().item(List.of()));

        EodhdResolvedSymbol result = await(resolver.resolve("BRK.B"));

        assertEquals("BRK.B.US", result.providerSymbol());
        verify(client, never()).search(any(), any(), any(), any());
    }

    @Test
    void givenUnchangedPersistedMapping_whenResolvingAgain_thenSkipsSearch() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("AAPL")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("AAPL", "NASDAQ", "US", Currency.USD))));
        when(client.search("AAPL", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("AAPL", "US", "USA", "USD"))));
        await(resolver.resolve("AAPL"));
        ArgumentCaptor<EodhdSymbolMapping> persisted = ArgumentCaptor.forClass(EodhdSymbolMapping.class);
        verify(store).upsertSymbolMapping(persisted.capture());
        Mockito.clearInvocations(client, store);
        when(store.findSymbolMapping("AAPL"))
                .thenReturn(Uni.createFrom().item(Optional.of(persisted.getValue())));

        assertEquals("AAPL.US", await(resolver.resolve("AAPL")).providerSymbol());

        verify(client, never()).search(any(), any(), any(), any());
        verify(store, never()).upsertSymbolMapping(any());
    }

    private EodhdSymbolResolver resolver(Map<String, String> overrides) {
        return new EodhdSymbolResolver(
                client,
                catalog,
                store,
                listings,
                currencyResolver,
                API_KEY,
                new ReactiveRateLimiter("test", 60_000, Duration.ofMinutes(1)),
                overrides);
    }

    private EodhdExchange usExchange() {
        return new EodhdExchange(
                "US", "USA Stocks", "XNAS,XNYS", "USA", "USD", "US", "USA", true,
                OffsetDateTime.now());
    }

    private EodhdSearchResponse search(String code, String exchange, String country, String currency) {
        return new EodhdSearchResponse(code, exchange, code + " Inc", "Common Stock", country, currency,
                null, true);
    }

    private EodhdResolvedSymbol await(Uni<EodhdResolvedSymbol> result) {
        return result.subscribe().withSubscriber(UniAssertSubscriber.create()).awaitItem().getItem();
    }
}

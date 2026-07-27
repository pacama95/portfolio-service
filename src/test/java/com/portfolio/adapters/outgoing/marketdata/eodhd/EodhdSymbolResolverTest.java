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
import org.eclipse.microprofile.config.Config;

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
    void givenEnglishCountryName_whenEodhdUsesIso3Name_thenResolvesQualifiedSymbol() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("AAPL")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("AAPL", "NASDAQ", "United States", Currency.USD))));
        when(client.search("AAPL", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("AAPL", "US", "USA", "USD"))));

        assertEquals("AAPL.US", await(resolver.resolve("AAPL")).providerSymbol());
    }

    @Test
    void givenEquivalentCountryRepresentationsAcrossTransactions_whenResolving_thenDoesNotConflict() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("AAPL")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("AAPL", "NASDAQ", "US", Currency.USD),
                new InstrumentListing("AAPL", "NASDAQ", "United States", Currency.USD))));
        when(client.search("AAPL", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("AAPL", "US", "USA", "USD"))));

        assertEquals("AAPL.US", await(resolver.resolve("AAPL")).providerSymbol());
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

    @Test
    void givenBlankCanonicalSymbol_whenResolving_thenFailsBeforeAnyDependencyCall() {
        resolver.resolve(" ")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.SymbolResolution.class);

        verify(catalog, never()).exchanges();
    }

    @Test
    void givenUniqueExactResultWithoutMetadata_whenResolving_thenUsesUniqueSearchSource() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("TSLA")).thenReturn(Uni.createFrom().item(List.of()));
        when(client.search("TSLA", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("TSLA", "US", "USA", "USD"))));

        assertEquals("TSLA.US", await(resolver.resolve("TSLA")).providerSymbol());
        ArgumentCaptor<EodhdSymbolMapping> mapping = ArgumentCaptor.forClass(EodhdSymbolMapping.class);
        verify(store).upsertSymbolMapping(mapping.capture());
        assertEquals(EodhdResolutionSource.UNIQUE_SEARCH, mapping.getValue().resolutionSource());
    }

    @Test
    void givenNullAndIrrelevantSearchRows_whenResolving_thenRejectsEveryNonCandidate() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("ABC", "NASDAQ", "GB", Currency.GBP))));
        when(client.search("ABC", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search(null, "US", "USA", "USD"),
                search("OTHER", "US", "USA", "USD"),
                search("ABC", null, "USA", "USD"),
                search("ABC", " ", "USA", "USD"),
                search("ABC", "UNKNOWN", "USA", "USD"),
                search("ABC", "US", "USA", "USD"))));

        resolver.resolve("ABC")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.SymbolResolution.class);
    }

    @Test
    void givenNullSearchEnvelope_whenResolving_thenFailsAsMissingData() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of()));
        when(client.search("ABC", API_KEY, "json", null)).thenReturn(Uni.createFrom().nullItem());

        resolver.resolve("ABC")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.MissingData.class);
    }

    @Test
    void givenMetadataWithNoOptionalHints_whenResolving_thenExactUniqueResultIsEnough() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("ABC", null, null, null))));
        when(client.search("ABC", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("ABC", "US", null, null))));

        assertEquals("ABC.US", await(resolver.resolve("ABC")).providerSymbol());
    }

    @Test
    void givenCatalogCurrencyFallback_whenMatchingMetadata_thenUsesExchangeCurrency() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("ABC", "US", null, Currency.USD))));
        when(client.search("ABC", API_KEY, "json", "US")).thenReturn(Uni.createFrom().item(List.of(
                search("ABC", "US", null, null))));

        assertEquals("USD", await(resolver.resolve("ABC")).rawCurrency());
    }

    @Test
    void givenCountryOrCurrencyConflicts_whenResolving_thenFailsWithoutSearch() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("COUNTRY")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("COUNTRY", "US", "US", Currency.USD),
                new InstrumentListing("COUNTRY", "US", "GB", Currency.USD))));
        when(listings.findBySymbol("CURRENCY")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("CURRENCY", "US", "US", Currency.USD),
                new InstrumentListing("CURRENCY", "US", "US", Currency.GBP))));

        assertResolutionFailure(resolver.resolve("COUNTRY"));
        assertResolutionFailure(resolver.resolve("CURRENCY"));
        verify(client, never()).search(any(), any(), any(), any());
    }

    @Test
    void givenMalformedOrUnknownConfiguredOverride_whenResolving_thenFailsExplicitly() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol(any())).thenReturn(Uni.createFrom().item(List.of()));

        resolver = resolver(Map.of("ABC", "ABC"));
        assertResolutionFailure(resolver.resolve("ABC"));
        resolver = resolver(Map.of("ABC", "ABC."));
        assertResolutionFailure(resolver.resolve("ABC"));
        resolver = resolver(Map.of("ABC", "ABC.UNKNOWN"));
        assertResolutionFailure(resolver.resolve("ABC"));
    }

    @Test
    void givenInactiveSearchExchange_whenResolvingNewMapping_thenRejectsIt() {
        EodhdExchange inactive = new EodhdExchange(
                "US", "USA Stocks", null, "USA", "USD", "US", "USA", false, OffsetDateTime.now());
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(inactive)));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of()));
        when(client.search("ABC", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("ABC", "US", "USA", "USD"))));

        assertResolutionFailure(resolver.resolve("ABC"));
    }

    @Test
    void givenConfigBackedOverride_whenUsingCdiConstructor_thenExtractsOnlyOverrideProperties() {
        Config config = Mockito.mock(Config.class);
        when(config.getPropertyNames()).thenReturn(List.of(
                "application.other", "application.market-data.eodhd.symbol-overrides.abc"));
        when(config.getOptionalValue("application.market-data.eodhd.symbol-overrides.abc", String.class))
                .thenReturn(Optional.of("ABC.US"));
        resolver = new EodhdSymbolResolver(
                client, catalog, store, listings, currencyResolver, API_KEY,
                new ReactiveRateLimiter("test", 60_000, Duration.ofMinutes(1)), config);
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of()));

        assertEquals("ABC.US", await(resolver.resolve("ABC")).providerSymbol());
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

    private void assertResolutionFailure(Uni<EodhdResolvedSymbol> result) {
        result.subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.SymbolResolution.class);
    }
}

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
        resolver = new EodhdSymbolResolver(
                client,
                catalog,
                store,
                listings,
                currencyResolver,
                API_KEY,
                new ReactiveRateLimiter("test", 60_000, Duration.ofMinutes(1)));
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

    /**
     * The whole reason listing metadata exists. A broker's exchange label matches neither EODHD's
     * code (MC) nor its MIC (XMAD), but country and currency identify the listing, so the single
     * remaining candidate must still resolve instead of failing as unmatched.
     */
    @Test
    void givenBrokerExchangeLabelUnknownToEodhd_whenCountryAndCurrencyMatch_thenStillResolves() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(madridExchange())));
        when(listings.findBySymbol("EBRO")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("EBRO", "BOLSA DE MADRID", "ES", Currency.EUR))));
        when(client.search("EBRO", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("EBRO", "MC", "Spain", "EUR"))));

        assertEquals("EBRO.MC", await(resolver.resolve("EBRO")).providerSymbol());
    }

    @Test
    void givenBrokerLabelMatchesNothingAndCountryDiffers_whenResolving_thenFailsInsteadOfGuessing() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("ABC", "BOLSA DE MADRID", "ES", Currency.EUR))));
        when(client.search("ABC", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("ABC", "US", "USA", "USD"))));

        assertResolutionFailure(resolver.resolve("ABC"));
    }

    @Test
    void givenAmbiguousResults_whenExchangeHintMatchesOne_thenNarrowsToThatListing() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange(), lseExchange())));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("ABC", "XLON", null, null))));
        when(client.search("ABC", API_KEY, "json", "LSE")).thenReturn(Uni.createFrom().item(List.of(
                search("ABC", "US", "USA", "USD"),
                search("ABC", "LSE", "UK", "GBX"))));

        assertEquals("ABC.LSE", await(resolver.resolve("ABC")).providerSymbol());
    }

    @Test
    void givenEnglishCountryName_whenEodhdUsesIso3Name_thenResolvesQualifiedSymbol() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("AAPL")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("AAPL", "NASDAQ", "United States", Currency.USD))));
        when(client.search("AAPL", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                // Search metadata can be incomplete or inconsistent; the catalog's ISO aliases
                // remain authoritative for the selected exchange.
                search("AAPL", "US", "Canada", "USD"))));

        assertEquals("AAPL.US", await(resolver.resolve("AAPL")).providerSymbol());
    }

    @Test
    void givenCountryNamesMissing_whenCatalogHasIso2_thenResolvesQualifiedSymbol() {
        EodhdExchange exchange = new EodhdExchange(
                "US", "USA Stocks", "XNAS,XNYS", null, "USD", "US", "USA", true,
                OffsetDateTime.now());
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(exchange)));
        when(listings.findBySymbol("AAPL")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("AAPL", "NASDAQ", "United States", Currency.USD))));
        when(client.search("AAPL", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("AAPL", "US", null, "USD"))));

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
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(lseExchange())));
        when(listings.findBySymbol("BARC")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("BARC", "XLON", "GB", Currency.GBP))));
        when(client.search("BARC", API_KEY, "json", "LSE")).thenReturn(Uni.createFrom().item(List.of(
                search("BARC", "LSE", "UK", "GBX"))));

        assertEquals("BARC.LSE", await(resolver.resolve("BARC")).providerSymbol());
        verify(client).search("BARC", API_KEY, "json", "LSE");
    }

    @Test
    void givenNoMetadataAndAmbiguousExactResults_whenResolving_thenFailsExplicitly() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange(), lseExchange())));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of()));
        when(client.search("ABC", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("ABC", "US", "USA", "USD"),
                search("ABC", "LSE", "UK", "GBP"))));

        assertResolutionFailure(resolver.resolve("ABC"));

        verify(store, never()).upsertSymbolMapping(any());
    }

    @Test
    void givenConflictingTransactionListings_whenResolving_thenDoesNotGuessOrSearch() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("ABC", "NASDAQ", "US", Currency.USD),
                new InstrumentListing("ABC", "LSE", "GB", Currency.GBP))));

        assertResolutionFailure(resolver.resolve("ABC"));

        verify(client, never()).search(any(), any(), any(), any());
    }

    @Test
    void givenDottedCanonicalTicker_whenResolving_thenSearchesItWholeAndNeverSplitsOnTheDot() {
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange())));
        when(listings.findBySymbol("BRK.B")).thenReturn(Uni.createFrom().item(List.of()));
        when(client.search("BRK.B", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("BRK.B", "US", "USA", "USD"))));

        assertEquals("BRK.B.US", await(resolver.resolve("BRK.B")).providerSymbol());
        verify(client).search("BRK.B", API_KEY, "json", null);
    }

    @Test
    void givenPersistedMapping_whenResolving_thenReturnsItWithoutTouchingCatalogOrSearch() {
        when(store.findSymbolMapping("AAPL")).thenReturn(Uni.createFrom().item(Optional.of(
                new EodhdSymbolMapping("AAPL", "AAPL.US", "US", "USD",
                        EodhdResolutionSource.TRANSACTION_METADATA, OffsetDateTime.now()))));

        assertEquals(new EodhdResolvedSymbol("AAPL", "AAPL.US", "US", "USD"), await(resolver.resolve("aapl")));

        verify(catalog, never()).exchanges();
        verify(listings, never()).findBySymbol(any());
        verify(client, never()).search(any(), any(), any(), any());
        verify(store, never()).upsertSymbolMapping(any());
    }

    @Test
    void givenOperatorManagedMapping_whenResolving_thenTrustsItWithoutRediscovery() {
        when(store.findSymbolMapping("DELISTED")).thenReturn(Uni.createFrom().item(Optional.of(
                new EodhdSymbolMapping("DELISTED", "DELISTED.US", "US", "USD",
                        EodhdResolutionSource.MANUAL, OffsetDateTime.now()))));

        assertEquals("DELISTED.US", await(resolver.resolve("DELISTED")).providerSymbol());

        verify(client, never()).search(any(), any(), any(), any());
    }

    @Test
    void givenBlankCanonicalSymbol_whenResolving_thenFailsBeforeAnyDependencyCall() {
        assertResolutionFailure(resolver.resolve(" "));

        verify(store, never()).findSymbolMapping(any());
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

        assertResolutionFailure(resolver.resolve("ABC"));
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
    void givenExchangeHintMatchingSeveralExchanges_whenResolving_thenSendsNoSearchFilter() {
        EodhdExchange duplicateName = new EodhdExchange(
                "US2", "USA Stocks", null, "USA", "USD", "US", "USA", true, OffsetDateTime.now());
        when(catalog.exchanges()).thenReturn(Uni.createFrom().item(List.of(usExchange(), duplicateName)));
        when(listings.findBySymbol("ABC")).thenReturn(Uni.createFrom().item(List.of(
                new InstrumentListing("ABC", "USA Stocks", "US", Currency.USD))));
        when(client.search("ABC", API_KEY, "json", null)).thenReturn(Uni.createFrom().item(List.of(
                search("ABC", "US", "USA", "USD"))));

        assertEquals("ABC.US", await(resolver.resolve("ABC")).providerSymbol());
        verify(client).search("ABC", API_KEY, "json", null);
    }

    private EodhdExchange usExchange() {
        return new EodhdExchange(
                "US", "USA Stocks", "XNAS,XNYS", "USA", "USD", "US", "USA", true,
                OffsetDateTime.now());
    }

    private EodhdExchange lseExchange() {
        return new EodhdExchange(
                "LSE", "London Stock Exchange", "XLON", "UK", "GBX", "GB", "GBR", true,
                OffsetDateTime.now());
    }

    private EodhdExchange madridExchange() {
        return new EodhdExchange(
                "MC", "Madrid Exchange", "XMAD", "Spain", "EUR", "ES", "ESP", true,
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

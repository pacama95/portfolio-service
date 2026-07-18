package com.portfolio.core.application.service;

import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.QuoteSource;
import com.portfolio.core.model.SpotQuote;
import com.portfolio.core.model.SpotQuoteKind;
import com.portfolio.core.ports.outgoing.MarketDataProviderPort;
import com.portfolio.core.ports.outgoing.MarketDataStorePort;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataServiceTest {

    private final MarketDataStorePort store = Mockito.mock(MarketDataStorePort.class);
    private final MarketDataProviderPort provider = Mockito.mock(MarketDataProviderPort.class);
    private MarketDataService service;

    @BeforeEach
    void setUp() {
        service = new MarketDataService(store, provider, Duration.ofMinutes(15), Duration.ofHours(1));
    }

    @Test
    void givenFreshSpotInStore_whenGetSpotPrice_thenNoProviderCall() {
        when(store.findSpotQuote(SpotQuoteKind.PRICE, "AAPL")).thenReturn(Uni.createFrom().item(Optional.of(
                new SpotQuote(SpotQuoteKind.PRICE, "AAPL", new BigDecimal("190"), null, null, null,
                        OffsetDateTime.now(), QuoteSource.PROVIDER))));

        BigDecimal price = service.getSpotPrice("AAPL").await().indefinitely();

        assertEquals(0, new BigDecimal("190").compareTo(price));
        verify(provider, never()).fetchSpotPrice(any());
    }

    @Test
    void givenStaleSpot_whenGetSpotPrice_thenFetchesAndWritesBack() {
        when(store.findSpotQuote(SpotQuoteKind.PRICE, "AAPL")).thenReturn(Uni.createFrom().item(Optional.of(
                new SpotQuote(SpotQuoteKind.PRICE, "AAPL", new BigDecimal("180"), null, null, null,
                        OffsetDateTime.now().minusHours(2), QuoteSource.PROVIDER))));
        when(provider.fetchSpotPrice("AAPL")).thenReturn(Uni.createFrom().item(new BigDecimal("191")));
        when(store.upsertSpotQuote(any(SpotQuote.class))).thenAnswer(inv -> {
            SpotQuote quote = inv.getArgument(0, SpotQuote.class);
            return Uni.createFrom().item(quote);
        });

        BigDecimal price = service.getSpotPrice("AAPL").await().indefinitely();

        assertEquals(0, new BigDecimal("191").compareTo(price));
        verify(store).upsertSpotQuote(any());
    }

    @Test
    void givenFullPriceHistoryInStore_whenGetPriceHistory_thenNoProviderCall() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);
        List<PriceHistoryEntry> stored = List.of(
                new PriceHistoryEntry("AAPL", from, new BigDecimal("100"), null, Currency.USD, OffsetDateTime.now()),
                new PriceHistoryEntry("AAPL", to, new BigDecimal("101"), null, Currency.USD, OffsetDateTime.now()));
        when(store.findPrices("AAPL", from, to)).thenReturn(Uni.createFrom().item(stored));

        List<PriceHistoryEntry> result = service.getPriceHistory("AAPL", from, to).await().indefinitely();

        assertEquals(2, result.size());
        verify(provider, never()).fetchPriceHistory(any(), any(), any());
    }

    @Test
    void givenMissingDays_whenGetPriceHistory_thenFetchesGapsAndWritesBack() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);
        when(store.findPrices(eq("AAPL"), eq(from), eq(to)))
                .thenReturn(Uni.createFrom().item(List.of(
                        new PriceHistoryEntry("AAPL", from, new BigDecimal("100"), null, Currency.USD, OffsetDateTime.now()))))
                .thenReturn(Uni.createFrom().item(List.of(
                        new PriceHistoryEntry("AAPL", from, new BigDecimal("100"), null, Currency.USD, OffsetDateTime.now()),
                        new PriceHistoryEntry("AAPL", to, new BigDecimal("101"), null, Currency.USD, OffsetDateTime.now()))));
        when(store.hasCoverage("PRICE", "AAPL", from, to)).thenReturn(Uni.createFrom().item(false));
        when(provider.fetchPriceHistory("AAPL", to, to)).thenReturn(Uni.createFrom().item(List.of(
                new PriceHistoryEntry("AAPL", to, new BigDecimal("101"), null, Currency.USD, OffsetDateTime.now()))));
        when(store.upsertPrices(any())).thenReturn(Uni.createFrom().voidItem());
        when(store.recordCoverage(any(), any(), any(), any(), any())).thenReturn(Uni.createFrom().voidItem());

        List<PriceHistoryEntry> result = service.getPriceHistory("AAPL", from, to).await().indefinitely();

        assertEquals(2, result.size());
        verify(provider).fetchPriceHistory("AAPL", to, to);
        verify(store).upsertPrices(any());
    }

    @Test
    void givenSameCurrency_whenGetFxRate_thenReturnsOne() {
        BigDecimal rate = service.getFxRate(Currency.USD, Currency.USD).await().indefinitely();

        assertEquals(0, BigDecimal.ONE.compareTo(rate));
        verify(store, never()).findSpotQuote(any(), any());
        verify(provider, never()).fetchFxRate(any(), any());
    }

    @Test
    void givenFreshFxInStore_whenGetFxRate_thenNoProviderCall() {
        when(store.findSpotQuote(SpotQuoteKind.FX, "EUR/USD")).thenReturn(Uni.createFrom().item(Optional.of(
                new SpotQuote(SpotQuoteKind.FX, "EUR/USD", new BigDecimal("1.10"), null,
                        Currency.EUR, Currency.USD, OffsetDateTime.now(), QuoteSource.PROVIDER))));

        BigDecimal rate = service.getFxRate(Currency.EUR, Currency.USD).await().indefinitely();

        assertEquals(0, new BigDecimal("1.10").compareTo(rate));
        verify(provider, never()).fetchFxRate(any(), any());
    }

    @Test
    void givenStaleFx_whenGetFxRate_thenFetchesAndWritesBack() {
        when(store.findSpotQuote(SpotQuoteKind.FX, "EUR/USD")).thenReturn(Uni.createFrom().item(Optional.of(
                new SpotQuote(SpotQuoteKind.FX, "EUR/USD", new BigDecimal("1.05"), null,
                        Currency.EUR, Currency.USD, OffsetDateTime.now().minusHours(3), QuoteSource.PROVIDER))));
        when(provider.fetchFxRate(Currency.EUR, Currency.USD)).thenReturn(Uni.createFrom().item(new BigDecimal("1.12")));
        when(store.upsertSpotQuote(any(SpotQuote.class))).thenAnswer(inv -> {
            SpotQuote quote = inv.getArgument(0, SpotQuote.class);
            return Uni.createFrom().item(quote);
        });

        BigDecimal rate = service.getFxRate(Currency.EUR, Currency.USD).await().indefinitely();

        assertEquals(0, new BigDecimal("1.12").compareTo(rate));
        verify(store).upsertSpotQuote(any());
    }

    @Test
    void givenCoverageMarked_whenGetPriceHistory_thenSkipsProvider() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);
        List<PriceHistoryEntry> partial = List.of(
                new PriceHistoryEntry("AAPL", from, new BigDecimal("100"), null, Currency.USD, OffsetDateTime.now()));
        when(store.findPrices("AAPL", from, to)).thenReturn(Uni.createFrom().item(partial));
        when(store.hasCoverage("PRICE", "AAPL", from, to)).thenReturn(Uni.createFrom().item(true));

        List<PriceHistoryEntry> result = service.getPriceHistory("AAPL", from, to).await().indefinitely();

        assertEquals(1, result.size());
        verify(provider, never()).fetchPriceHistory(any(), any(), any());
    }

    @Test
    void givenSameCurrency_whenGetFxHistory_thenReturnsIdentityRates() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);

        List<FxRateEntry> result = service.getFxHistory(Currency.USD, Currency.USD, from, to).await().indefinitely();

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(entry -> entry.rate().compareTo(BigDecimal.ONE) == 0));
        verify(store, never()).findFxRates(any(), any(), any(), any());
    }

    @Test
    void givenMissingFxDays_whenGetFxHistory_thenFetchesGapsAndWritesBack() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);
        when(store.findFxRates(Currency.EUR, Currency.USD, from, to))
                .thenReturn(Uni.createFrom().item(List.of(
                        new FxRateEntry(Currency.EUR, Currency.USD, from, new BigDecimal("1.10"), OffsetDateTime.now()))))
                .thenReturn(Uni.createFrom().item(List.of(
                        new FxRateEntry(Currency.EUR, Currency.USD, from, new BigDecimal("1.10"), OffsetDateTime.now()),
                        new FxRateEntry(Currency.EUR, Currency.USD, to, new BigDecimal("1.11"), OffsetDateTime.now()))));
        when(store.hasCoverage("FX", "EUR/USD", from, to)).thenReturn(Uni.createFrom().item(false));
        when(provider.fetchFxHistory(Currency.EUR, Currency.USD, to, to)).thenReturn(Uni.createFrom().item(List.of(
                new FxRateEntry(Currency.EUR, Currency.USD, to, new BigDecimal("1.11"), OffsetDateTime.now()))));
        when(store.upsertFxRates(any())).thenReturn(Uni.createFrom().voidItem());
        when(store.recordCoverage(any(), any(), any(), any(), any())).thenReturn(Uni.createFrom().voidItem());

        List<FxRateEntry> result = service.getFxHistory(Currency.EUR, Currency.USD, from, to).await().indefinitely();

        assertEquals(2, result.size());
        verify(provider).fetchFxHistory(Currency.EUR, Currency.USD, to, to);
        verify(store).upsertFxRates(any());
    }

    @Test
    void givenNoStoredSpotQuote_whenGetSpotPrice_thenFetchesFromProvider() {
        when(store.findSpotQuote(SpotQuoteKind.PRICE, "AAPL")).thenReturn(Uni.createFrom().item(Optional.empty()));
        when(provider.fetchSpotPrice("AAPL")).thenReturn(Uni.createFrom().item(new BigDecimal("192")));
        when(store.upsertSpotQuote(any(SpotQuote.class))).thenAnswer(inv -> {
            SpotQuote quote = inv.getArgument(0, SpotQuote.class);
            return Uni.createFrom().item(quote);
        });

        BigDecimal price = service.getSpotPrice("AAPL").await().indefinitely();

        assertEquals(0, new BigDecimal("192").compareTo(price));
        verify(provider).fetchSpotPrice("AAPL");
    }

    @Test
    void givenQuoteWithNullAsOf_whenGetSpotPrice_thenFetchesFromProvider() {
        when(store.findSpotQuote(SpotQuoteKind.PRICE, "AAPL")).thenReturn(Uni.createFrom().item(Optional.of(
                new SpotQuote(SpotQuoteKind.PRICE, "AAPL", new BigDecimal("180"), null, null, null,
                        null, QuoteSource.PROVIDER))));
        when(provider.fetchSpotPrice("AAPL")).thenReturn(Uni.createFrom().item(new BigDecimal("193")));
        when(store.upsertSpotQuote(any(SpotQuote.class))).thenAnswer(inv -> {
            SpotQuote quote = inv.getArgument(0, SpotQuote.class);
            return Uni.createFrom().item(quote);
        });

        BigDecimal price = service.getSpotPrice("AAPL").await().indefinitely();

        assertEquals(0, new BigDecimal("193").compareTo(price));
        verify(provider).fetchSpotPrice("AAPL");
    }

    @Test
    void givenNoStoredFxQuote_whenGetFxRate_thenFetchesFromProvider() {
        when(store.findSpotQuote(SpotQuoteKind.FX, "EUR/USD")).thenReturn(Uni.createFrom().item(Optional.empty()));
        when(provider.fetchFxRate(Currency.EUR, Currency.USD)).thenReturn(Uni.createFrom().item(new BigDecimal("1.15")));
        when(store.upsertSpotQuote(any(SpotQuote.class))).thenAnswer(inv -> {
            SpotQuote quote = inv.getArgument(0, SpotQuote.class);
            return Uni.createFrom().item(quote);
        });

        BigDecimal rate = service.getFxRate(Currency.EUR, Currency.USD).await().indefinitely();

        assertEquals(0, new BigDecimal("1.15").compareTo(rate));
        verify(provider).fetchFxRate(Currency.EUR, Currency.USD);
    }

    @Test
    void givenUncoveredMissingDays_whenGetPriceHistory_thenFetchesFromProvider() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);
        when(store.findPrices("AAPL", from, to))
                .thenReturn(Uni.createFrom().item(List.of()))
                .thenReturn(Uni.createFrom().item(List.of(
                        new PriceHistoryEntry("AAPL", from, new BigDecimal("100"), null, Currency.USD, OffsetDateTime.now()),
                        new PriceHistoryEntry("AAPL", to, new BigDecimal("101"), null, Currency.USD, OffsetDateTime.now()))));
        when(store.hasCoverage("PRICE", "AAPL", from, to)).thenReturn(Uni.createFrom().item(false));
        when(provider.fetchPriceHistory("AAPL", from, to)).thenReturn(Uni.createFrom().item(List.of(
                new PriceHistoryEntry("AAPL", from, new BigDecimal("100"), null, Currency.USD, OffsetDateTime.now()),
                new PriceHistoryEntry("AAPL", to, new BigDecimal("101"), null, Currency.USD, OffsetDateTime.now()))));
        when(store.upsertPrices(any())).thenReturn(Uni.createFrom().voidItem());
        when(store.recordCoverage(any(), any(), any(), any(), any())).thenReturn(Uni.createFrom().voidItem());

        List<PriceHistoryEntry> result = service.getPriceHistory("AAPL", from, to).await().indefinitely();

        assertEquals(2, result.size());
        verify(provider).fetchPriceHistory("AAPL", from, to);
    }

    @Test
    void givenManualPrice_whenSetManualPrice_thenUpsertsManualQuote() {
        when(store.upsertSpotQuote(any(SpotQuote.class))).thenAnswer(inv -> {
            SpotQuote quote = inv.getArgument(0, SpotQuote.class);
            return Uni.createFrom().item(quote);
        });

        SpotQuote quote = service.setManualPrice("aapl", new BigDecimal("199.50"), Currency.USD)
                .await().indefinitely();

        assertEquals("AAPL", quote.symbol());
        assertEquals(0, new BigDecimal("199.50").compareTo(quote.value()));
        assertEquals(Currency.USD, quote.currency());
        assertEquals(QuoteSource.MANUAL, quote.source());
        verify(store).upsertSpotQuote(any());
    }
}

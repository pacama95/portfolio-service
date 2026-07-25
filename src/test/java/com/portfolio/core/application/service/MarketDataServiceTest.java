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
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import io.smallrye.mutiny.subscription.UniEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

        BigDecimal price = service.getSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

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

        BigDecimal price = service.getSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

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

        List<PriceHistoryEntry> result = service.getPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(2, result.size());
        verify(provider, never()).fetchPriceHistory(any(), any(), any());
    }

    @Test
    void givenRangeEndsTodayAndSettledDaysCached_whenGetPriceHistory_thenNoProviderCall() {
        // Regression: today's EOD close is never recorded as coverage, so a to=today request must
        // not treat today as an uncovered gap and re-fetch on every call.
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate from = today.minusDays(3);
        List<PriceHistoryEntry> stored = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(yesterday); day = day.plusDays(1)) {
            stored.add(new PriceHistoryEntry("AAPL", day, new BigDecimal("100"), null, Currency.USD,
                    OffsetDateTime.now()));
        }
        when(store.findPrices("AAPL", from, today)).thenReturn(Uni.createFrom().item(stored));

        List<PriceHistoryEntry> result = service.getPriceHistory("AAPL", from, today)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(stored.size(), result.size());
        verify(provider, never()).fetchPriceHistory(any(), any(), any());
        verify(store, never()).hasCoverage(any(), any(), any(), any());
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

        List<PriceHistoryEntry> result = service.getPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(2, result.size());
        verify(provider).fetchPriceHistory("AAPL", to, to);
        verify(store).upsertPrices(any());
    }

    @Test
    void givenConcurrentRequestsForSameGap_whenGetPriceHistory_thenCoalescesIntoOneProviderCall() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);
        List<PriceHistoryEntry> fullyFetched = List.of(
                new PriceHistoryEntry("AAPL", from, new BigDecimal("100"), null, Currency.USD, OffsetDateTime.now()),
                new PriceHistoryEntry("AAPL", to, new BigDecimal("101"), null, Currency.USD, OffsetDateTime.now()));
        when(store.findPrices("AAPL", from, to))
                .thenReturn(Uni.createFrom().item(List.of()))
                .thenReturn(Uni.createFrom().item(List.of()))
                .thenReturn(Uni.createFrom().item(fullyFetched));
        when(store.hasCoverage("PRICE", "AAPL", from, to)).thenReturn(Uni.createFrom().item(false));
        when(store.upsertPrices(any())).thenReturn(Uni.createFrom().voidItem());
        when(store.recordCoverage(any(), any(), any(), any(), any())).thenReturn(Uni.createFrom().voidItem());

        @SuppressWarnings("unchecked")
        AtomicReference<UniEmitter<List<PriceHistoryEntry>>> emitterHolder = new AtomicReference<>();
        Uni<List<PriceHistoryEntry>> pendingProviderCall = Uni.createFrom().emitter(
                (UniEmitter<? super List<PriceHistoryEntry>> e) ->
                        emitterHolder.set((UniEmitter<List<PriceHistoryEntry>>) e));
        when(provider.fetchPriceHistory("AAPL", from, to)).thenReturn(pendingProviderCall);

        // Both requests reach the coalescing point before the provider call resolves,
        // mirroring two concurrent frontend requests racing on the same symbol/range.
        UniAssertSubscriber<List<PriceHistoryEntry>> first =
                service.getPriceHistory("AAPL", from, to).subscribe().withSubscriber(UniAssertSubscriber.create());
        UniAssertSubscriber<List<PriceHistoryEntry>> second =
                service.getPriceHistory("AAPL", from, to).subscribe().withSubscriber(UniAssertSubscriber.create());

        first.assertNotTerminated();
        second.assertNotTerminated();

        emitterHolder.get().complete(fullyFetched);

        assertEquals(2, first.awaitItem().getItem().size());
        assertEquals(2, second.awaitItem().getItem().size());
        verify(provider, times(1)).fetchPriceHistory("AAPL", from, to);
        verify(store, times(1)).upsertPrices(any());
        verify(store, times(1)).recordCoverage(any(), any(), any(), any(), any());
    }

    @Test
    void givenSameCurrency_whenGetFxRate_thenReturnsOne() {
        BigDecimal rate = service.getFxRate(Currency.USD, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(0, BigDecimal.ONE.compareTo(rate));
        verify(store, never()).findSpotQuote(any(), any());
        verify(provider, never()).fetchFxRate(any(), any());
    }

    @Test
    void givenFreshFxInStore_whenGetFxRate_thenNoProviderCall() {
        when(store.findSpotQuote(SpotQuoteKind.FX, "EUR/USD")).thenReturn(Uni.createFrom().item(Optional.of(
                new SpotQuote(SpotQuoteKind.FX, "EUR/USD", new BigDecimal("1.10"), null,
                        Currency.EUR, Currency.USD, OffsetDateTime.now(), QuoteSource.PROVIDER))));

        BigDecimal rate = service.getFxRate(Currency.EUR, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

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

        BigDecimal rate = service.getFxRate(Currency.EUR, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

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

        List<PriceHistoryEntry> result = service.getPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(1, result.size());
        verify(provider, never()).fetchPriceHistory(any(), any(), any());
    }

    @Test
    void givenSameCurrency_whenGetFxHistory_thenReturnsIdentityRates() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);

        List<FxRateEntry> result = service.getFxHistory(Currency.USD, Currency.USD, from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

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

        List<FxRateEntry> result = service.getFxHistory(Currency.EUR, Currency.USD, from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

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

        BigDecimal price = service.getSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

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

        BigDecimal price = service.getSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

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

        BigDecimal rate = service.getFxRate(Currency.EUR, Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

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

        List<PriceHistoryEntry> result = service.getPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(2, result.size());
        verify(provider).fetchPriceHistory("AAPL", from, to);
    }

    @Test
    void givenFutureToDate_whenGetPriceHistory_thenClampsToToday() {
        LocalDate from = LocalDate.now().minusDays(1);
        LocalDate today = LocalDate.now();
        LocalDate futureTo = today.plusDays(30);
        when(store.findPrices("AAPL", from, today)).thenReturn(Uni.createFrom().item(List.of()));
        when(store.hasCoverage("PRICE", "AAPL", from, today)).thenReturn(Uni.createFrom().item(false));
        when(provider.fetchPriceHistory(eq("AAPL"), any(), any())).thenReturn(Uni.createFrom().item(List.of(
                new PriceHistoryEntry("AAPL", from, new BigDecimal("100"), null, Currency.USD, OffsetDateTime.now())
        )));
        when(store.upsertPrices(any())).thenReturn(Uni.createFrom().voidItem());
        when(store.recordCoverage(any(), any(), any(), any(), any())).thenReturn(Uni.createFrom().voidItem());

        service.getPriceHistory("AAPL", from, futureTo)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        verify(store, never()).findPrices("AAPL", from, futureTo);
        verify(store, times(2)).findPrices("AAPL", from, today);
        verify(provider, never()).fetchPriceHistory(eq("AAPL"), any(), eq(futureTo));
    }

    @Test
    void givenToIsToday_whenGetPriceHistory_thenNeverRecordsCoverageForToday() {
        LocalDate today = LocalDate.now();
        when(store.findPrices("AAPL", today, today)).thenReturn(Uni.createFrom().item(List.of()));
        when(store.hasCoverage("PRICE", "AAPL", today, today)).thenReturn(Uni.createFrom().item(false));
        when(provider.fetchPriceHistory("AAPL", today, today)).thenReturn(Uni.createFrom().item(List.of()));
        when(store.upsertPrices(any())).thenReturn(Uni.createFrom().voidItem());

        service.getPriceHistory("AAPL", today, today)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        verify(store, never()).recordCoverage(any(), any(), any(), any(), any());
    }

    @Test
    void givenRangeEndingYesterday_whenGetPriceHistory_thenRecordsCoverageUpToRequestedTo() {
        LocalDate from = LocalDate.now().minusDays(5);
        LocalDate to = LocalDate.now().minusDays(1);
        when(store.findPrices("AAPL", from, to)).thenReturn(Uni.createFrom().item(List.of()));
        when(store.hasCoverage("PRICE", "AAPL", from, to)).thenReturn(Uni.createFrom().item(false));
        when(provider.fetchPriceHistory(eq("AAPL"), any(), any())).thenReturn(Uni.createFrom().item(List.of(
                new PriceHistoryEntry("AAPL", from, new BigDecimal("100"), null, Currency.USD, OffsetDateTime.now())
        )));
        when(store.upsertPrices(any())).thenReturn(Uni.createFrom().voidItem());
        when(store.recordCoverage(any(), any(), any(), any(), any())).thenReturn(Uni.createFrom().voidItem());

        service.getPriceHistory("AAPL", from, to)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        verify(store).recordCoverage("PRICE", "AAPL", from, to, "twelvedata");
    }

    @Test
    void givenFutureToDate_whenGetFxHistory_thenClampsToToday() {
        LocalDate from = LocalDate.now().minusDays(1);
        LocalDate today = LocalDate.now();
        LocalDate futureTo = today.plusDays(30);
        when(store.findFxRates(Currency.EUR, Currency.USD, from, today)).thenReturn(Uni.createFrom().item(List.of()));
        when(store.hasCoverage("FX", "EUR/USD", from, today)).thenReturn(Uni.createFrom().item(false));
        when(provider.fetchFxHistory(eq(Currency.EUR), eq(Currency.USD), any(), any()))
                .thenReturn(Uni.createFrom().item(List.of(
                        new FxRateEntry(Currency.EUR, Currency.USD, from, new BigDecimal("1.1"), OffsetDateTime.now())
                )));
        when(store.upsertFxRates(any())).thenReturn(Uni.createFrom().voidItem());
        when(store.recordCoverage(any(), any(), any(), any(), any())).thenReturn(Uni.createFrom().voidItem());

        service.getFxHistory(Currency.EUR, Currency.USD, from, futureTo)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        verify(store, never()).findFxRates(Currency.EUR, Currency.USD, from, futureTo);
        verify(store, times(2)).findFxRates(Currency.EUR, Currency.USD, from, today);
    }

    @Test
    void givenManualPrice_whenSetManualPrice_thenUpsertsManualQuote() {
        when(store.upsertSpotQuote(any(SpotQuote.class))).thenAnswer(inv -> {
            SpotQuote quote = inv.getArgument(0, SpotQuote.class);
            return Uni.createFrom().item(quote);
        });

        SpotQuote quote = service.setManualPrice("aapl", new BigDecimal("199.50"), Currency.USD)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals("AAPL", quote.symbol());
        assertEquals(0, new BigDecimal("199.50").compareTo(quote.value()));
        assertEquals(Currency.USD, quote.currency());
        assertEquals(QuoteSource.MANUAL, quote.source());
        verify(store).upsertSpotQuote(any());
    }

    @Test
    void givenOldManualQuote_whenGetSpotPrice_thenNeverRefetchesFromProvider() {
        // MANUAL quotes never go stale on their own -- only clearManualPrice reverts to the
        // provider -- so an old asOf timestamp must not trigger a provider re-fetch.
        when(store.findSpotQuote(SpotQuoteKind.PRICE, "AAPL")).thenReturn(Uni.createFrom().item(Optional.of(
                new SpotQuote(SpotQuoteKind.PRICE, "AAPL", new BigDecimal("199.50"), Currency.USD, null, null,
                        OffsetDateTime.now().minusDays(30), QuoteSource.MANUAL))));

        BigDecimal price = service.getSpotPrice("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(0, new BigDecimal("199.50").compareTo(price));
        verify(provider, never()).fetchSpotPrice(any());
    }

    @Test
    void givenSymbol_whenClearManualPrice_thenDeletesStoredQuote() {
        when(store.deleteSpotQuote(SpotQuoteKind.PRICE, "AAPL")).thenReturn(Uni.createFrom().voidItem());

        service.clearManualPrice("aapl")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        verify(store).deleteSpotQuote(SpotQuoteKind.PRICE, "AAPL");
    }
}

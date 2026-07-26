package com.portfolio.integration.persistence;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.QuoteSource;
import com.portfolio.core.model.SpotQuote;
import com.portfolio.core.model.SpotQuoteKind;
import com.portfolio.core.ports.outgoing.MarketDataStorePort;
import com.portfolio.integration.IntegrationTestProfile;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class MarketDataStoreIT {

    @Inject
    MarketDataStorePort store;

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @SuppressWarnings("unused")
    private final ConnectionHolder connectionHolder =
            () -> CDI.current().select(AgroalDataSource.class).get().getConnection();

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/market-data-seed.sql")
    void givenSeededPrices_whenFindPrices_thenReturnsRange(UniAsserter asserter) {
        asserter.assertThat(
                () -> store.findPrices("AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2)),
                prices -> {
                    assertEquals(2, prices.size());
                    assertEquals(0, new BigDecimal("100.000000").compareTo(prices.getFirst().closePrice()));
                    assertEquals(0, new BigDecimal("101.000000").compareTo(prices.get(1).closePrice()));
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true)
    void givenEmptyStore_whenUpsertPrices_thenFindReturnsInserted(UniAsserter asserter) {
        OffsetDateTime now = OffsetDateTime.parse("2024-02-01T12:00:00Z");
        List<PriceHistoryEntry> entries = List.of(new PriceHistoryEntry(
                "MSFT",
                LocalDate.of(2024, 2, 1),
                new BigDecimal("300.000000"),
                new BigDecimal("300.000000"),
                Currency.USD,
                now));

        asserter.execute(() -> store.upsertPrices(entries));
        asserter.assertThat(
                () -> store.findPrices("MSFT", LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 1)),
                prices -> {
                    assertEquals(1, prices.size());
                    assertEquals(0, new BigDecimal("300.000000").compareTo(prices.getFirst().closePrice()));
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/market-data-seed.sql")
    void givenMixOfNewAndExistingRows_whenUpsertPricesBatched_thenInsertsAndUpdatesInOneCall(UniAsserter asserter) {
        OffsetDateTime now = OffsetDateTime.parse("2024-03-01T12:00:00Z");
        List<PriceHistoryEntry> entries = List.of(
                // AAPL/2024-01-01 already exists in the seed (close=100.000000) -> update path.
                new PriceHistoryEntry(
                        "AAPL", LocalDate.of(2024, 1, 1), new BigDecimal("999.000000"), null, Currency.USD, now),
                // Two brand-new rows -> insert path, in the same batched call.
                new PriceHistoryEntry(
                        "GOOG", LocalDate.of(2024, 3, 1), new BigDecimal("150.000000"),
                        new BigDecimal("150.000000"), Currency.USD, now),
                new PriceHistoryEntry(
                        "GOOG", LocalDate.of(2024, 3, 2), new BigDecimal("151.000000"),
                        new BigDecimal("151.000000"), Currency.USD, now));

        asserter.execute(() -> store.upsertPrices(entries));
        asserter.assertThat(
                () -> store.findPrices("AAPL", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)),
                prices -> assertEquals(0, new BigDecimal("999.000000").compareTo(prices.getFirst().closePrice())));
        asserter.assertThat(
                () -> store.findPrices("GOOG", LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 2)),
                prices -> {
                    assertEquals(2, prices.size());
                    assertEquals(0, new BigDecimal("150.000000").compareTo(prices.get(0).closePrice()));
                    assertEquals(0, new BigDecimal("151.000000").compareTo(prices.get(1).closePrice()));
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/market-data-seed.sql")
    void givenSeededSpotQuote_whenFindAndUpsert_thenReadsAndUpdates(UniAsserter asserter) {
        asserter.assertThat(
                () -> store.findSpotQuote(SpotQuoteKind.PRICE, "AAPL"),
                optional -> {
                    assertTrue(optional.isPresent());
                    assertEquals(0, new BigDecimal("190.50000000").compareTo(optional.get().value()));
                });

        SpotQuote updated = new SpotQuote(
                SpotQuoteKind.PRICE,
                "AAPL",
                new BigDecimal("200.00000000"),
                Currency.USD,
                null,
                null,
                OffsetDateTime.parse("2024-01-03T12:00:00Z"),
                QuoteSource.MANUAL);

        asserter.assertThat(
                () -> store.upsertSpotQuote(updated),
                quote -> assertEquals(0, new BigDecimal("200.00000000").compareTo(quote.value())));
        asserter.assertThat(
                () -> store.findSpotQuote(SpotQuoteKind.PRICE, "AAPL"),
                optional -> {
                    assertTrue(optional.isPresent());
                    assertEquals(QuoteSource.MANUAL, optional.get().source());
                    assertEquals(0, new BigDecimal("200.00000000").compareTo(optional.get().value()));
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/market-data-seed.sql")
    void givenSeededSpotQuote_whenDeleteSpotQuote_thenNoLongerFound(UniAsserter asserter) {
        asserter.assertThat(
                () -> store.findSpotQuote(SpotQuoteKind.PRICE, "AAPL"),
                optional -> assertTrue(optional.isPresent()));

        asserter.execute(() -> store.deleteSpotQuote(SpotQuoteKind.PRICE, "AAPL"));

        asserter.assertThat(
                () -> store.findSpotQuote(SpotQuoteKind.PRICE, "AAPL"),
                optional -> assertFalse(optional.isPresent()));
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/market-data-seed.sql")
    void givenSeededFx_whenFindAndUpsert_thenWorks(UniAsserter asserter) {
        asserter.assertThat(
                () -> store.findFxRates(
                        Currency.EUR, Currency.USD, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)),
                rates -> {
                    assertEquals(1, rates.size());
                    assertEquals(0, new BigDecimal("1.10000000").compareTo(rates.getFirst().rate()));
                });

        FxRateEntry entry = new FxRateEntry(
                Currency.EUR,
                Currency.USD,
                LocalDate.of(2024, 1, 2),
                new BigDecimal("1.12000000"),
                OffsetDateTime.parse("2024-01-02T12:00:00Z"));

        asserter.execute(() -> store.upsertFxRates(List.of(entry)));
        asserter.assertThat(
                () -> store.findFxRates(
                        Currency.EUR, Currency.USD, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2)),
                rates -> assertEquals(2, rates.size()));
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true)
    void givenProviderWrites_whenPersisted_thenStoresActualProviderForEveryDataKind(UniAsserter asserter) {
        OffsetDateTime fetchedAt = OffsetDateTime.parse("2024-04-01T12:00:00Z");
        PriceHistoryEntry price = new PriceHistoryEntry(
                "AAPL", LocalDate.of(2024, 4, 1), new BigDecimal("170.00"),
                new BigDecimal("170.00"), Currency.USD, fetchedAt);
        FxRateEntry fx = new FxRateEntry(
                Currency.EUR, Currency.USD, LocalDate.of(2024, 4, 1), new BigDecimal("1.08"), fetchedAt);
        SpotQuote spot = new SpotQuote(
                SpotQuoteKind.PRICE, "AAPL", new BigDecimal("170.00"), Currency.USD,
                null, null, fetchedAt, QuoteSource.PROVIDER);

        asserter.execute(() -> store.upsertPrices(List.of(price), "eodhd"));
        asserter.execute(() -> store.upsertFxRates(List.of(fx), "eodhd"));
        asserter.execute(() -> store.upsertSpotQuote(spot, "eodhd").replaceWithVoid());
        asserter.assertThat(() -> storedProvider("price_history"), provider -> assertEquals("eodhd", provider));
        asserter.assertThat(() -> storedProvider("fx_rate_history"), provider -> assertEquals("eodhd", provider));
        asserter.assertThat(() -> storedProvider("spot_quotes"), provider -> assertEquals("eodhd", provider));
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true)
    void givenNoCoverage_whenRecordCoverage_thenHasCoverageTrue(UniAsserter asserter) {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);

        asserter.assertThat(
                () -> store.hasCoverage("PRICE", "AAPL", from, to),
                covered -> assertFalse(covered));
        asserter.execute(() -> store.recordCoverage("PRICE", "AAPL", from, to, "twelvedata"));
        asserter.assertThat(
                () -> store.hasCoverage("PRICE", "AAPL", from, to),
                covered -> assertTrue(covered));
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true)
    void givenCoverageAlreadyRecorded_whenRecordCoverageAgain_thenIgnoresDuplicateWithoutThrowing(
            UniAsserter asserter) {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 2);

        asserter.execute(() -> store.recordCoverage("PRICE", "AAPL", from, to, "twelvedata"));
        // Simulates a second concurrent request racing on the identical (kind, symbol, from, to)
        // tuple that already violates the market_data_coverage unique constraint.
        asserter.execute(() -> store.recordCoverage("PRICE", "AAPL", from, to, "twelvedata"));
        asserter.assertThat(
                () -> store.hasCoverage("PRICE", "AAPL", from, to),
                Assertions::assertTrue);
    }

    private io.smallrye.mutiny.Uni<String> storedProvider(String table) {
        return sessionFactory.withSession(session -> session
                .createNativeQuery("select provider from " + table, String.class)
                .getSingleResult());
    }
}

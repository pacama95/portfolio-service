package com.portfolio.integration.persistence;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.core.api.dataset.ExpectedDataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionSortOrder;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import com.portfolio.integration.IntegrationTestProfile;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class TransactionRepositoryIT {

    private static final UserId USER_A = UserId.of("user-a");
    private static final UserId USER_B = UserId.of("user-b");
    private static final UUID AAPL_BUY_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID MSFT_BUY_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Inject
    TransactionRepository repository;

    @SuppressWarnings("unused")
    private final ConnectionHolder connectionHolder =
            () -> CDI.current().select(AgroalDataSource.class).get().getConnection();

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenFindById_thenRespectsUserScope(UniAsserter asserter) {
        asserter.assertThat(
                () -> repository.findById(USER_A, AAPL_BUY_ID),
                optional -> {
                    assertTrue(optional.isPresent());
                    assertEquals("AAPL", optional.get().ticker());
                });
        asserter.assertThat(
                () -> repository.findById(USER_B, AAPL_BUY_ID),
                optional -> assertTrue(optional.isEmpty()));
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenFindAll_thenReturnsUserRows(UniAsserter asserter) {
        asserter.assertThat(
                () -> repository.findAll(USER_A),
                list -> assertEquals(3, list.size()));
        asserter.assertThat(
                () -> repository.findAll(USER_B),
                list -> assertEquals(1, list.size()));
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenFindByTicker_thenFilters(UniAsserter asserter) {
        asserter.assertThat(
                () -> repository.findByTicker(USER_A, "AAPL"),
                list -> {
                    assertEquals(2, list.size());
                    assertTrue(list.stream().allMatch(tx -> "AAPL".equals(tx.ticker())));
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenSearch_thenAppliesFilters(UniAsserter asserter) {
        asserter.assertThat(
                () -> repository.search(
                        USER_A,
                        "AAPL",
                        TransactionType.BUY,
                        LocalDate.of(2024, 1, 1),
                        LocalDate.of(2024, 1, 3),
                        TransactionSortOrder.ASC),
                list -> {
                    assertEquals(1, list.size());
                    assertEquals(AAPL_BUY_ID, list.getFirst().id());
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenCount_thenReturnsScopedCounts(UniAsserter asserter) {
        asserter.assertThat(() -> repository.count(USER_A), count -> assertEquals(3L, count));
        asserter.assertThat(() -> repository.countByTicker(USER_A, "AAPL"), count -> assertEquals(2L, count));
        asserter.assertThat(() -> repository.count(USER_B), count -> assertEquals(1L, count));
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenFindDistinctTickers_thenReturnsAllTickers(UniAsserter asserter) {
        asserter.assertThat(
                () -> repository.findDistinctTickers(),
                tickers -> {
                    assertTrue(tickers.containsAll(List.of("AAPL", "MSFT", "GOOG")));
                    assertEquals(3, tickers.size());
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransaction_whenDeleteById_thenRemovesOnlyOwnerRow(UniAsserter asserter) {
        asserter.assertThat(
                () -> repository.deleteById(USER_B, AAPL_BUY_ID),
                deleted -> assertFalse(deleted));
        asserter.assertThat(
                () -> repository.deleteById(USER_A, AAPL_BUY_ID),
                deleted -> assertTrue(deleted));
        asserter.assertThat(
                () -> repository.findById(USER_A, AAPL_BUY_ID),
                optional -> assertTrue(optional.isEmpty()));
        asserter.assertThat(
                () -> repository.findById(USER_A, MSFT_BUY_ID),
                optional -> assertTrue(optional.isPresent()));
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    @ExpectedDataSet(
            value = "datasets/transactions-after-update.yml",
            ignoreCols = {"created_at", "updated_at", "commission_currency", "exchange", "country",
                    "is_fractional", "fractional_multiplier", "notes"},
            orderBy = {"id"})
    void givenSeededTransaction_whenSaveUpdate_thenExpectedDatasetMatches(UniAsserter asserter) {
        OffsetDateTime now = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        Transaction updated = new Transaction(
                AAPL_BUY_ID,
                USER_A,
                "AAPL",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("15.000000"),
                new BigDecimal("105.000000"),
                new BigDecimal("1.000000"),
                Currency.USD,
                LocalDate.of(2024, 1, 1),
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "Apple Inc",
                now,
                now);
        asserter.assertThat(
                () -> repository.save(updated),
                saved -> {
                    assertEquals(0, new BigDecimal("15.000000").compareTo(saved.quantity()));
                    assertEquals("Apple Inc", saved.companyName());
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true)
    void givenNewTransaction_whenSave_thenPersistedAndReadable(UniAsserter asserter) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2024-06-01T00:00:00Z");
        Transaction created = new Transaction(
                id,
                USER_A,
                "NVDA",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("3.000000"),
                new BigDecimal("400.000000"),
                BigDecimal.ZERO,
                Currency.USD,
                LocalDate.of(2024, 6, 1),
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "NVIDIA",
                now,
                now);

        asserter.assertThat(
                () -> repository.save(created),
                saved -> {
                    assertEquals(id, saved.id());
                    assertEquals("NVDA", saved.ticker());
                });
        asserter.assertThat(
                () -> repository.findById(USER_A, id),
                optional -> {
                    assertTrue(optional.isPresent());
                    assertEquals(0, new BigDecimal("3.000000").compareTo(optional.get().quantity()));
                });
    }
}

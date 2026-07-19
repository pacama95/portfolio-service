package com.portfolio.integration.persistence;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.core.model.IngestionRun;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.outgoing.PriceIngestionRunRepository;
import com.portfolio.integration.IntegrationTestProfile;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class PriceIngestionRunRepositoryIT {

    private static final UUID COMPLETED_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Duration STALE_RUN_TIMEOUT = Duration.ofHours(3);

    @Inject
    PriceIngestionRunRepository repository;

    @SuppressWarnings("unused")
    private final ConnectionHolder connectionHolder =
            () -> CDI.current().select(AgroalDataSource.class).get().getConnection();

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/ingestion-run-seed.sql")
    void givenCompletedSeed_whenFindLatest_thenReturnsCompleted(UniAsserter asserter) {
        asserter.assertThat(
                () -> repository.findLatest(),
                optional -> {
                    assertTrue(optional.isPresent());
                    assertEquals(COMPLETED_ID, optional.get().id());
                    assertEquals(IngestionRun.Status.COMPLETED, optional.get().status());
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/ingestion-run-seed.sql")
    void givenOnlyCompleted_whenHasActiveRun_thenFalse(UniAsserter asserter) {
        asserter.assertThat(() -> repository.hasActiveRun(STALE_RUN_TIMEOUT), active -> assertFalse(active));
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/ingestion-run-seed.sql")
    void givenCompletedSeed_whenFindById_thenReturnsRun(UniAsserter asserter) {
        asserter.assertThat(
                () -> repository.findById(COMPLETED_ID),
                optional -> {
                    assertTrue(optional.isPresent());
                    assertEquals("AAPL", optional.get().ticker());
                    assertEquals(IngestionRun.Status.COMPLETED, optional.get().status());
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/ingestion-run-seed.sql")
    void givenPendingSaved_whenHasActiveRun_thenTrue(UniAsserter asserter) {
        UUID pendingId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        IngestionRun pending = new IngestionRun(
                pendingId,
                UserId.of("user-a"),
                IngestionRun.Status.PENDING,
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 2),
                "MSFT",
                false,
                0,
                0,
                null,
                null,
                null,
                now,
                now);

        asserter.assertThat(
                () -> repository.save(pending),
                saved -> assertEquals(IngestionRun.Status.PENDING, saved.status()));
        asserter.assertThat(() -> repository.hasActiveRun(STALE_RUN_TIMEOUT), active -> assertTrue(active));
        asserter.assertThat(
                () -> repository.findById(pendingId),
                optional -> {
                    assertTrue(optional.isPresent());
                    assertEquals(IngestionRun.Status.PENDING, optional.get().status());
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/ingestion-run-seed.sql")
    void givenStaleRunningRun_whenHasActiveRun_thenReapedToFailedAndFalse(UniAsserter asserter) {
        UUID staleId = UUID.randomUUID();
        OffsetDateTime startedLongAgo = OffsetDateTime.now().minus(STALE_RUN_TIMEOUT).minusMinutes(1);
        IngestionRun stale = new IngestionRun(
                staleId,
                UserId.of("user-a"),
                IngestionRun.Status.RUNNING,
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 2),
                "MSFT",
                false,
                1,
                0,
                null,
                startedLongAgo,
                null,
                startedLongAgo,
                startedLongAgo);

        asserter.assertThat(
                () -> repository.save(stale),
                saved -> assertEquals(IngestionRun.Status.RUNNING, saved.status()));
        asserter.assertThat(() -> repository.hasActiveRun(STALE_RUN_TIMEOUT), active -> assertFalse(active));
        asserter.assertThat(
                () -> repository.findById(staleId),
                optional -> {
                    assertTrue(optional.isPresent());
                    assertEquals(IngestionRun.Status.FAILED, optional.get().status());
                    assertNotNull(optional.get().errorMessage());
                    assertNotNull(optional.get().completedAt());
                });
    }
}

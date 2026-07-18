package com.portfolio.integration.rest;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.integration.IntegrationTestProfile;
import com.portfolio.integration.WireMockMarketDataResource;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class PriceIngestionApiIT {

    private static final String USER_A = "user-a";

    @SuppressWarnings("unused")
    private final ConnectionHolder connectionHolder =
            () -> CDI.current().select(AgroalDataSource.class).get().getConnection();

    @BeforeEach
    void resetWireMock() {
        WireMockMarketDataResource.server.resetAll();
        WireMockMarketDataResource.stubDefaults();
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenTicker_whenTriggerRun_thenCompletesAndLatestSucceeds() throws Exception {
        String runId = given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "from", "2024-01-01",
                        "to", "2024-01-02",
                        "ticker", "AAPL",
                        "includeBackfill", false
                ))
                .when()
                .post("/api/price-ingestion/runs")
                .then()
                .statusCode(202)
                .body("runId", notNullValue())
                .extract()
                .path("runId");

        assertTrue(waitUntil(() ->
                "COMPLETED".equals(given()
                        .header("X-User-Id", USER_A)
                        .when()
                        .get("/api/price-ingestion/runs/latest")
                        .then()
                        .statusCode(200)
                        .body("id", equalTo(runId))
                        .extract()
                        .path("status")), Duration.ofSeconds(15)));

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/price-ingestion/runs/latest")
                .then()
                .statusCode(200)
                .body("id", equalTo(runId))
                .body("status", equalTo("COMPLETED"))
                .body("ticker", equalTo("AAPL"))
                .body("symbolsRequested", equalTo(1))
                .body("symbolsCompleted", equalTo(1));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenNoRuns_whenGetLatest_thenNotFound() {
        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/price-ingestion/runs/latest")
                .then()
                .statusCode(404);
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/ingestion-run-seed.sql")
    void givenCompletedSeed_whenGetLatest_thenSuccess() {
        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/price-ingestion/runs/latest")
                .then()
                .statusCode(200)
                .body("id", equalTo("33333333-3333-3333-3333-333333333333"))
                .body("status", equalTo("COMPLETED"))
                .body("ticker", equalTo("AAPL"))
                .body("symbolsRequested", equalTo(1))
                .body("symbolsCompleted", equalTo(1));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/ingestion-run-pending-seed.sql"
    })
    void givenActivePendingRun_whenTriggerAgain_thenConflict() {
        given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "from", "2024-01-01",
                        "to", "2024-01-02",
                        "ticker", "AAPL",
                        "includeBackfill", false
                ))
                .when()
                .post("/api/price-ingestion/runs")
                .then()
                .statusCode(409)
                .body("message", notNullValue());
    }

    private static boolean waitUntil(Supplier<Boolean> condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Boolean.TRUE.equals(condition.get())) {
                    return true;
                }
            } catch (AssertionError | Exception ignored) {
                // keep polling
            }
            Thread.sleep(50);
        }
        try {
            return Boolean.TRUE.equals(condition.get());
        } catch (AssertionError | Exception ex) {
            return false;
        }
    }
}

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
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class DailyHistoryApiIT {

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
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/market-data-seed.sql"
    })
    void givenSeededLedger_whenGetPositions_thenReturnsDailySnapshots() {
        given()
                .header("X-User-Id", USER_A)
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-05")
                .when()
                .get("/api/daily-history/positions")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("ticker", hasItem("AAPL"))
                .body("find { it.ticker == 'AAPL' && it.snapshotDate == '2024-01-01' }.sharesOwned",
                        equalTo(10.0f));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/market-data-seed.sql"
    })
    void givenSeededLedger_whenGetPositionsWithTicker_thenFilters() {
        given()
                .header("X-User-Id", USER_A)
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-05")
                .queryParam("ticker", "AAPL")
                .when()
                .get("/api/daily-history/positions")
                .then()
                .statusCode(200)
                .body("ticker", hasItem("AAPL"))
                .body("findAll { it.ticker == 'MSFT' }", hasSize(0));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/market-data-seed.sql"
    })
    void givenSeededPrices_whenGetPrices_thenReturnsHistory() {
        given()
                .header("X-User-Id", USER_A)
                .queryParam("symbols", "AAPL")
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/prices")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("find { it.priceDate == '2024-01-01' }.closePrice", equalTo(100.0f))
                .body("find { it.priceDate == '2024-01-02' }.closePrice", equalTo(101.0f));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/market-data-seed.sql"
    })
    void givenSeededData_whenGetValuation_thenReturnsDailyValuations() {
        given()
                .header("X-User-Id", USER_A)
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/portfolio/valuation")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].date", notNullValue())
                .body("[0].valuationCurrency", equalTo("USD"))
                .body("[0].totalMarketValue", notNullValue());
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/market-data-seed.sql"
    })
    void givenSeededLedger_whenGetCapitalFlows_thenReturnsFlows() {
        given()
                .header("X-User-Id", USER_A)
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-05")
                .when()
                .get("/api/daily-history/portfolio/capital-flows")
                .then()
                .statusCode(200)
                .body("flows", hasSize(3))
                .body("flows.ticker", hasItem("AAPL"))
                .body("flows.ticker", hasItem("MSFT"));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/market-data-seed.sql"
    })
    void givenSeededData_whenGetPerformanceInputs_thenReturnsBundle() {
        given()
                .header("X-User-Id", USER_A)
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/portfolio/performance-inputs")
                .then()
                .statusCode(200)
                .body("valuations.size()", greaterThanOrEqualTo(1))
                .body("capitalFlows.size()", greaterThanOrEqualTo(1))
                .body("incompleteDays", notNullValue());
    }

    @Test
    void givenMissingDates_whenGetPositions_thenBadRequest() {
        given()
                .header("X-User-Id", USER_A)
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/positions")
                .then()
                .statusCode(400);
    }
}

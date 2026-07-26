package com.portfolio.integration.rest;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.portfolio.integration.IntegrationTestProfile;
import com.portfolio.integration.WireMockMarketDataResource;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DataSet(cleanBefore = true)
    void givenTwelveDataUnavailable_whenGetPrices_thenFallsBackToEodhdAndPersistsProvenance()
            throws Exception {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/time_series"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/search/TSLA"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{
                                  "Code": "TSLA",
                                  "Exchange": "US",
                                  "Name": "Tesla Inc",
                                  "Type": "Common Stock",
                                  "Country": "USA",
                                  "Currency": "USD",
                                  "isPrimary": true
                                }]
                                """)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/eod/TSLA.US"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [
                                  {"date":"2024-01-01","close":248.5,"adjusted_close":248.5},
                                  {"date":"2024-01-02","close":251.25,"adjusted_close":251.25}
                                ]
                                """)));

        given()
                .header("X-User-Id", USER_A)
                .queryParam("symbols", "TSLA")
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/prices")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("find { it.priceDate == '2024-01-02' }.closePrice", equalTo(251.25f));

        WireMockMarketDataResource.server.verify(getRequestedFor(urlPathEqualTo("/time_series")));
        WireMockMarketDataResource.server.verify(getRequestedFor(urlPathEqualTo("/api/exchanges-list/")));
        WireMockMarketDataResource.server.verify(getRequestedFor(urlPathEqualTo("/api/search/TSLA")));
        WireMockMarketDataResource.server.verify(getRequestedFor(urlPathEqualTo("/api/eod/TSLA.US")));
        try (Connection connection = CDI.current().select(AgroalDataSource.class).get().getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select distinct provider from price_history where symbol = 'TSLA'")) {
            result.next();
            assertEquals("eodhd", result.getString(1));
        }
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

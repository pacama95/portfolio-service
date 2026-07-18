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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class PortfolioApiIT {

    private static final String USER_A = "user-a";

    @SuppressWarnings("unused")
    private final ConnectionHolder connectionHolder =
            () -> CDI.current().select(AgroalDataSource.class).get().getConnection();

    @BeforeEach
    void resetWireMock() {
        WireMockMarketDataResource.server.resetAll();
        WireMockMarketDataResource.stubDefaults();
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/price"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"price\":\"190.5\"}")));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenGetSummary_thenReturnsAggregates() {
        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/portfolio/summary")
                .then()
                .statusCode(200)
                .header("X-Request-Id", notNullValue())
                .body("valuationCurrency", equalTo("USD"))
                .body("totalPositions", equalTo(2))
                .body("activePositions", equalTo(2))
                .body("totalMarketValue", greaterThan(0f))
                .body("totalCost", greaterThan(0f));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenGetActiveSummary_thenReturnsActiveAggregates() {
        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/portfolio/summary/active")
                .then()
                .statusCode(200)
                .body("valuationCurrency", equalTo("USD"))
                .body("activePositions", equalTo(2))
                .body("totalMarketValue", greaterThan(0f));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenNoTransactions_whenGetSummary_thenEmptyPortfolio() {
        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/portfolio/summary")
                .then()
                .statusCode(200)
                .body("totalPositions", equalTo(0))
                .body("activePositions", equalTo(0));
    }
}

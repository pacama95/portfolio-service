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
import io.restassured.http.ContentType;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class PositionApiIT {

    private static final String USER = "position-user";
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
    @DataSet(cleanBefore = true)
    void givenBuy_whenGetPositions_thenDerivedPositionReturned() {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/price"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"price\":\"150.5\"}")));

        given()
                .header("X-User-Id", USER)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "GOOG",
                          "transactionType": "BUY",
                          "assetType": "COMMON_STOCK",
                          "quantity": 4,
                          "price": 100,
                          "currency": "USD",
                          "transactionDate": "2024-03-01",
                          "companyName": "Alphabet"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(201);

        given()
                .header("X-User-Id", USER)
                .when()
                .get("/api/positions/ticker/GOOG")
                .then()
                .statusCode(200)
                .body("ticker", equalTo("GOOG"))
                .body("totalQuantity", anyOf(equalTo(4), equalTo(4.0f), equalTo(4.0)))
                .body("isActive", equalTo(true))
                .body("currentPrice", anyOf(equalTo(150.5f), equalTo(150.5)));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenManualPrice_whenGetPositionAgain_thenNoProviderCallForFreshManualQuote() {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/price"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"price\":\"10\"}")));

        given()
                .header("X-User-Id", USER)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "TSLA",
                          "transactionType": "BUY",
                          "assetType": "COMMON_STOCK",
                          "quantity": 1,
                          "price": 200,
                          "currency": "USD",
                          "transactionDate": "2024-04-01"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(201);

        given()
                .header("X-User-Id", USER)
                .contentType(ContentType.JSON)
                .body("{\"price\": 250, \"currency\": \"USD\"}")
                .when()
                .put("/api/positions/ticker/TSLA/price")
                .then()
                .statusCode(200)
                .body("currentPrice", anyOf(equalTo(250), equalTo(250.0f), equalTo(250.0)));

        WireMockMarketDataResource.server.resetRequests();

        given()
                .header("X-User-Id", USER)
                .when()
                .get("/api/positions/ticker/TSLA")
                .then()
                .statusCode(200)
                .body("currentPrice", anyOf(equalTo(250), equalTo(250.0f), equalTo(250.0)));

        WireMockMarketDataResource.server.verify(0, getRequestedFor(urlPathMatching("/price.*")));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenListAllAndActive_thenReturnsDerivedPositions() {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/price"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"price\":\"190.5\"}")));

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions")
                .then()
                .statusCode(200)
                .body("$", hasSize(2));

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/active")
                .then()
                .statusCode(200)
                .body("$", hasSize(2));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenGetByTicker_thenReturnsPositionOrNotFound() {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/price"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"price\":\"190.5\"}")));

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/ticker/AAPL")
                .then()
                .statusCode(200)
                .body("ticker", equalTo("AAPL"))
                .body("totalQuantity", anyOf(equalTo(8), equalTo(8.0f), equalTo(8.0)))
                .body("isActive", equalTo(true));

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/ticker/UNKNOWN")
                .then()
                .statusCode(404);
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenExistsAndCounts_thenMatchPositions() {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/price"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"price\":\"190.5\"}")));

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/ticker/AAPL/exists")
                .then()
                .statusCode(200)
                .body(equalTo("true"));

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/ticker/UNKNOWN/exists")
                .then()
                .statusCode(200)
                .body(equalTo("false"));

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/count")
                .then()
                .statusCode(200)
                .body(equalTo("2"));

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/count/active")
                .then()
                .statusCode(200)
                .body(equalTo("2"));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededPosition_whenUpdatePrice_thenReturnsUpdatedCurrentPrice() {
        given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body("{\"price\": 250, \"currency\": \"USD\"}")
                .when()
                .put("/api/positions/ticker/AAPL/price")
                .then()
                .statusCode(200)
                .body("ticker", equalTo("AAPL"))
                .body("currentPrice", anyOf(equalTo(250), equalTo(250.0f), equalTo(250.0)));
    }
}

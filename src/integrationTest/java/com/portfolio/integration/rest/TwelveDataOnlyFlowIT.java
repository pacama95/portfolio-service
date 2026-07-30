package com.portfolio.integration.rest;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.integration.ProviderStubs;
import com.portfolio.integration.TwelveDataOnlyTestProfile;
import com.portfolio.integration.WireMockMarketDataResource;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The rollback configuration: TwelveData is the only provider. Beyond the happy paths, the
 * property that matters is inertness — the EODHD adapter, its reference tables, and its HTTP
 * endpoints must see zero activity even when TwelveData fails, because rollback promises that
 * removing EODHD from the chain fully disables it without redeploying code.
 */
@QuarkusTest
@TestProfile(TwelveDataOnlyTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class TwelveDataOnlyFlowIT {

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
    void givenHealthyTwelveData_whenGetPosition_thenSpotServedWithoutEodhd() throws Exception {
        ProviderStubs.twelveDataSpot("AAPL", "150.5");

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/ticker/AAPL")
                .then()
                .statusCode(200)
                .body("currentPrice", anyOf(equalTo(150.5f), equalTo(150.5)));

        ProviderStubs.verifyNoEodhdTraffic();
        assertEquals("twelvedata", scalar(
                "select provider from spot_quotes where kind = 'PRICE' and symbol = 'AAPL'"));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenHealthyTwelveData_whenGetPrices_thenHistoryServedWithoutEodhd() throws Exception {
        ProviderStubs.twelveDataSeries("AAPL", "USD", "2024-01-02:101.0", "2024-01-01:100.0");

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
                .body("find { it.priceDate == '2024-01-02' }.closePrice", equalTo(101.0f));

        ProviderStubs.verifyNoEodhdTraffic();
        assertEquals("twelvedata", scalar(
                "select distinct provider from price_history where symbol = 'AAPL'"));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenTwelveDataDown_whenGetPosition_thenFailsWithoutEverTryingEodhd() throws Exception {
        ProviderStubs.twelveDataDown();

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/ticker/AAPL")
                .then()
                .statusCode(503)
                .body("error", equalTo("MARKET_DATA_UNAVAILABLE"));

        // The rollback guarantee: with EODHD out of the chain, a failing primary does not
        // wake it up — no catalog refresh, no search, no reference-data writes.
        ProviderStubs.verifyNoEodhdTraffic();
        assertEquals("0", scalar("select count(*) from eodhd_exchanges"));
        assertEquals("0", scalar("select count(*) from eodhd_symbol_mappings"));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenTwelveDataRateLimited_whenGetPrices_thenReturns503WithRetryAfter() {
        ProviderStubs.twelveDataRateLimited();

        given()
                .header("X-User-Id", USER_A)
                .queryParam("symbols", "AAPL")
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/prices")
                .then()
                .statusCode(503)
                .header("Retry-After", notNullValue())
                .body("error", equalTo("RATE_LIMITED"));

        ProviderStubs.verifyNoEodhdTraffic();
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenFeeConversion_whenPosted_thenFxSpotServedWithoutEodhd() throws Exception {
        ProviderStubs.twelveDataFxRate("EUR/USD", "1.1");

        given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "SAP",
                          "transactionType": "BUY",
                          "assetType": "COMMON_STOCK",
                          "quantity": 1,
                          "price": 100,
                          "fees": 2,
                          "currency": "USD",
                          "commissionCurrency": "EUR",
                          "transactionDate": "2024-01-02",
                          "exchange": "NYSE",
                          "country": "US"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(201)
                .body("fees", equalTo(2.2f));

        ProviderStubs.verifyNoEodhdTraffic();
        assertEquals("twelvedata", scalar(
                "select provider from spot_quotes where kind = 'FX' and symbol = 'EUR/USD'"));
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = CDI.current().select(AgroalDataSource.class).get().getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }
}

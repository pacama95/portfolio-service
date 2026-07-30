package com.portfolio.integration.rest;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.integration.EodhdPrimaryTestProfile;
import com.portfolio.integration.ProviderStubs;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reversed chain: EODHD is primary, TwelveData is the fallback. A healthy EODHD leaves
 * TwelveData untouched; every typed EODHD failure — full outage, rate limit, unresolvable
 * symbol — falls through to TwelveData, and provenance records who actually answered.
 */
@QuarkusTest
@TestProfile(EodhdPrimaryTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class EodhdPrimaryFlowIT {

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
    @DataSet(value = "datasets/eodhd-reference-seed.yml", cleanBefore = true,
            executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenHealthyEodhdPrimary_whenGetPosition_thenTwelveDataUntouched() throws Exception {
        ProviderStubs.eodhdRealTime("AAPL.US", "190.5");

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/ticker/AAPL")
                .then()
                .statusCode(200)
                .body("currentPrice", anyOf(equalTo(190.5f), equalTo(190.5)));

        ProviderStubs.verifyNoTwelveDataTraffic();
        assertEquals("eodhd", scalar(
                "select provider from spot_quotes where kind = 'PRICE' and symbol = 'AAPL'"));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenEodhdFullyDown_whenGetPosition_thenTwelveDataServesSpot() throws Exception {
        ProviderStubs.eodhdDown();
        ProviderStubs.twelveDataSpot("AAPL", "150.5");

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/ticker/AAPL")
                .then()
                .statusCode(200)
                .body("currentPrice", anyOf(equalTo(150.5f), equalTo(150.5)));

        assertEquals("twelvedata", scalar(
                "select provider from spot_quotes where kind = 'PRICE' and symbol = 'AAPL'"));
    }

    @Test
    @DataSet(value = "datasets/eodhd-reference-seed.yml", cleanBefore = true)
    void givenEodhdRateLimited_whenGetPrices_thenTwelveDataServesHistory() throws Exception {
        ProviderStubs.eodhdEodRateLimited("AAPL.US", 30);
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

        assertEquals("twelvedata", scalar(
                "select distinct provider from price_history where symbol = 'AAPL'"));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenSymbolUnresolvableAtEodhd_whenGetPrices_thenTwelveDataAbsorbsIt() throws Exception {
        // Default stubs: catalog resolves but search finds nothing, so EODHD fails typed
        // with SymbolResolution and the router moves on instead of surfacing 422.
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
                .body("$", hasSize(2));

        assertEquals("twelvedata", scalar(
                "select distinct provider from price_history where symbol = 'AAPL'"));
        assertEquals("0", scalar(
                "select count(*) from eodhd_symbol_mappings where canonical_symbol = 'AAPL'"));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenEodhdForexDown_whenFeeConversionNeedsRate_thenTwelveDataServesFxSpot() throws Exception {
        ProviderStubs.eodhdRealTimeDown("EURUSD.FOREX");
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

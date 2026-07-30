package com.portfolio.integration.rest;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.core.api.dataset.ExpectedDataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.portfolio.integration.IntegrationTestProfile;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Whole-path provider flows: REST request in, TwelveData/EODHD over WireMock, Postgres state via
 * DBRider, REST response out. Focuses on what the EODHD outgoing layer adds — fallback, symbol
 * resolution and its persistence, quote-unit normalization, and write-time mapping invalidation.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class MarketDataProviderFlowIT {

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
    @ExpectedDataSet(value = "datasets/eodhd-after-spot-discovery.yml")
    void givenTwelveDataDown_whenSpotPrice_thenEodhdDiscoversSymbolFromTransactionMetadata() {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/price"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/search/AAPL"))
                .atPriority(1)
                .willReturn(WireMock.okJson("""
                        [{
                          "Code": "AAPL",
                          "Exchange": "US",
                          "Name": "Apple Inc",
                          "Type": "Common Stock",
                          "Country": "USA",
                          "Currency": "USD",
                          "isPrimary": true
                        }]
                        """)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/real-time/AAPL.US"))
                .atPriority(1)
                .willReturn(WireMock.okJson("{\"close\":190.5}")));

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/positions/ticker/AAPL")
                .then()
                .statusCode(200)
                .body("currentPrice", anyOf(equalTo(190.5f),
                        equalTo(190.5)));

        // The primary was attempted, and the seeded NASDAQ/US/USD metadata drove discovery.
        WireMockMarketDataResource.server.verify(getRequestedFor(urlPathEqualTo("/price")));
        WireMockMarketDataResource.server.verify(getRequestedFor(urlPathEqualTo("/api/exchanges-list/")));
        WireMockMarketDataResource.server.verify(getRequestedFor(urlPathEqualTo("/api/search/AAPL")));
        WireMockMarketDataResource.server.verify(getRequestedFor(urlPathEqualTo("/api/real-time/AAPL.US")));
    }

    @Test
    @DataSet(value = "datasets/eodhd-reference-seed.yml", cleanBefore = true)
    @ExpectedDataSet(value = "datasets/eodhd-prices-after-mapped-fallback.yml", orderBy = "price_date")
    void givenPersistedMapping_whenPriceHistoryFallsBack_thenNoCatalogOrSearchTraffic() throws Exception {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/time_series"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/eod/AAPL.US"))
                .atPriority(1)
                .willReturn(WireMock.okJson("""
                        [
                          {"date":"2024-01-01","close":111.5,"adjusted_close":111.5},
                          {"date":"2024-01-02","close":112.25,"adjusted_close":112.25}
                        ]
                        """)));

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
                .body("find { it.priceDate == '2024-01-02' }.closePrice",
                        equalTo(112.25f));

        // The mapping short-circuits resolution: no catalog refresh, no search, straight to EOD.
        WireMockMarketDataResource.server.verify(0, getRequestedFor(urlPathEqualTo("/api/exchanges-list/")));
        WireMockMarketDataResource.server.verify(0, getRequestedFor(urlPathMatching("/api/search/.*")));
        assertEquals("eodhd", scalar(
                "select provider from market_data_coverage "
                        + "where coverage_kind = 'PRICE' and symbol = 'AAPL' "
                        + "and from_date = '2024-01-01' and to_date = '2024-01-02'"));
    }

    @Test
    @DataSet(cleanBefore = true)
    @ExpectedDataSet(value = "datasets/eodhd-barc-after-discovery.yml", orderBy = "price_date")
    void givenXlonListingMetadata_whenDiscoveringBarc_thenSearchIsFilteredAndPenceNormalized()
            throws Exception {
        stubCatalogWithUsAndLse();
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/time_series"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/search/BARC"))
                .atPriority(1)
                .willReturn(WireMock.okJson("""
                        [{
                          "Code": "BARC",
                          "Exchange": "LSE",
                          "Name": "Barclays PLC",
                          "Type": "Common Stock",
                          "Country": "UK",
                          "Currency": "GBX",
                          "isPrimary": true
                        }]
                        """)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/eod/BARC.LSE"))
                .atPriority(1)
                .willReturn(WireMock.okJson("""
                        [
                          {"date":"2024-01-01","close":250.0,"adjusted_close":250.0},
                          {"date":"2024-01-02","close":252.5,"adjusted_close":252.5}
                        ]
                        """)));

        given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "BARC",
                          "transactionType": "BUY",
                          "assetType": "COMMON_STOCK",
                          "quantity": 10,
                          "price": 2.5,
                          "currency": "GBP",
                          "transactionDate": "2024-01-01",
                          "exchange": "XLON",
                          "country": "GB"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(201);

        given()
                .header("X-User-Id", USER_A)
                .queryParam("symbols", "BARC")
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/prices")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("find { it.priceDate == '2024-01-01' }.closePrice",
                        equalTo(2.5f))
                .body("find { it.priceDate == '2024-01-01' }.currency",
                        equalTo("GBP"));

        // XLON maps to exactly one catalog exchange, so it rides along as EODHD's search filter.
        WireMockMarketDataResource.server.verify(getRequestedFor(urlPathEqualTo("/api/search/BARC"))
                .withQueryParam("exchange", WireMock.equalTo("LSE")));
        assertEquals("BARC.LSE|LSE|GBX|TRANSACTION_METADATA", scalar(
                "select provider_symbol || '|' || exchange_code || '|' || raw_currency || '|' "
                        + "|| resolution_source from eodhd_symbol_mappings where canonical_symbol = 'BARC'"));
    }

    @Test
    @DataSet(value = "datasets/eodhd-reference-seed.yml", cleanBefore = true)
    void givenEodhdSubscriptionWarning_whenPriceHistory_thenNothingPersistedAndRequestFails()
            throws Exception {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/time_series"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/eod/TSLA.US"))
                .atPriority(1)
                .willReturn(WireMock.okJson("""
                        [
                          {"date":"2024-01-01","close":100.0,"adjusted_close":100.0},
                          {"date":"2024-01-02","close":101.0,"adjusted_close":101.0,
                           "warning":"limited history for your subscription"}
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
                .statusCode(503)
                .body("error", equalTo("MARKET_DATA_UNAVAILABLE"));

        // A truncated 200 must not leave partial bars or claim the range as covered.
        assertEquals("0", scalar("select count(*) from price_history where symbol = 'TSLA'"));
        assertEquals("0", scalar("select count(*) from market_data_coverage where symbol = 'TSLA'"));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenTwelveDataFxDown_whenFeeConversionNeedsRate_thenEodhdForexAnswersWithoutCatalog()
            throws Exception {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/exchange_rate"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/real-time/EURUSD.FOREX"))
                .atPriority(1)
                .willReturn(WireMock.okJson("{\"close\":1.1}")));

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

        // FX never needs equity reference data: forex symbol built directly, no catalog, no search.
        WireMockMarketDataResource.server.verify(getRequestedFor(urlPathEqualTo("/api/real-time/EURUSD.FOREX")));
        WireMockMarketDataResource.server.verify(0, getRequestedFor(urlPathEqualTo("/api/exchanges-list/")));
        WireMockMarketDataResource.server.verify(0, getRequestedFor(urlPathMatching("/api/search/.*")));
        assertEquals("eodhd", scalar(
                "select provider from spot_quotes where kind = 'FX' and symbol = 'EUR/USD'"));
    }

    @Test
    @DataSet(value = "datasets/eodhd-reference-seed.yml", cleanBefore = true,
            executeScriptsBefore = "datasets/transactions-seed.sql")
    @ExpectedDataSet(value = "datasets/eodhd-mappings-after-invalidation.yml", orderBy = "canonical_symbol")
    void givenTransactionWrites_whenTickersTouched_thenResolvedMappingDropsAndManualSurvives() {
        given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "AAPL",
                          "transactionType": "BUY",
                          "assetType": "COMMON_STOCK",
                          "quantity": 1,
                          "price": 100,
                          "currency": "USD",
                          "transactionDate": "2024-02-01",
                          "exchange": "NASDAQ",
                          "country": "US"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(201);

        given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "DELISTED",
                          "transactionType": "BUY",
                          "assetType": "COMMON_STOCK",
                          "quantity": 1,
                          "price": 10,
                          "currency": "USD",
                          "transactionDate": "2024-02-01",
                          "exchange": "NASDAQ",
                          "country": "US"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(201);
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenTwelveDataHealthy_whenPriceHistory_thenEodhdNeverContacted() throws Exception {
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

        // WireMock only receives provider traffic, so any /api/* hit would be an EODHD call.
        WireMockMarketDataResource.server.verify(0, getRequestedFor(urlPathMatching("/api/.*")));
        assertEquals("twelvedata", scalar(
                "select distinct provider from price_history where symbol = 'AAPL'"));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-barc-seed.sql")
    void givenGbpPosition_whenGetValuation_thenPricesAndFxBothTravelThroughTwelveData()
            throws Exception {
        ProviderStubs.twelveDataSeries("BARC", "GBP", "2024-01-02:2.525", "2024-01-01:2.5");
        ProviderStubs.twelveDataSeries("GBP/USD", "USD", "2024-01-02:1.28", "2024-01-01:1.25");

        given()
                .header("X-User-Id", USER_A)
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/portfolio/valuation")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[0].valuationCurrency", equalTo("USD"))
                // 10 shares x 2.50 GBP x 1.25, then x 2.525 x 1.28
                .body("[0].totalMarketValue", equalTo(31.25f))
                .body("[1].totalMarketValue", equalTo(32.32f));

        ProviderStubs.verifyNoEodhdTraffic();
        assertEquals("twelvedata", scalar(
                "select distinct provider from price_history where symbol = 'BARC'"));
        assertEquals("twelvedata", scalar(
                "select distinct provider from fx_rate_history "
                        + "where base_currency = 'GBP' and quote_currency = 'USD'"));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenAmbiguousExactMatches_whenNoListingMetadata_thenFailsWithoutPersistingAnything()
            throws Exception {
        stubCatalogWithUsAndLse();
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/time_series"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/search/NVO"))
                .atPriority(1)
                .willReturn(WireMock.okJson("""
                        [
                          {"Code":"NVO","Exchange":"US","Country":"USA","Currency":"USD"},
                          {"Code":"NVO","Exchange":"LSE","Country":"UK","Currency":"GBX"}
                        ]
                        """)));

        given()
                .header("X-User-Id", USER_A)
                .queryParam("symbols", "NVO")
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/prices")
                .then()
                .statusCode(503)
                .body("error", equalTo("MARKET_DATA_UNAVAILABLE"));

        // Ambiguity is never guessed away: no mapping row, and the EOD endpoint is never reached.
        WireMockMarketDataResource.server.verify(0, getRequestedFor(urlPathMatching("/api/eod/.*")));
        assertEquals("0", scalar(
                "select count(*) from eodhd_symbol_mappings where canonical_symbol = 'NVO'"));
    }

    private static void stubCatalogWithUsAndLse() {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/exchanges-list/"))
                .atPriority(1)
                .willReturn(WireMock.okJson("""
                        [
                          {"Name":"USA Stocks","Code":"US","OperatingMIC":"XNAS,XNYS",
                           "Country":"USA","Currency":"USD","CountryISO2":"US","CountryISO3":"USA"},
                          {"Name":"London Stock Exchange","Code":"LSE","OperatingMIC":"XLON",
                           "Country":"UK","Currency":"GBX","CountryISO2":"GB","CountryISO3":"GBR"}
                        ]
                        """)));
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = CDI.current().select(AgroalDataSource.class).get().getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }
}

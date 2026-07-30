package com.portfolio.integration.rest;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.integration.EodhdOnlyTestProfile;
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
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every market-data-consuming endpoint served by EODHD alone. No fallback exists, so this is
 * also where single-provider failure semantics surface: an unresolved symbol becomes 422 and a
 * provider rate limit becomes 503 + Retry-After, instead of being absorbed by the next provider.
 */
@QuarkusTest
@TestProfile(EodhdOnlyTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class EodhdOnlyFlowIT {

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
    void givenMappedSymbol_whenGetPosition_thenSpotServedByEodhdOnly() throws Exception {
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
    @DataSet(value = "datasets/eodhd-reference-seed.yml", cleanBefore = true)
    void givenMappedSymbol_whenGetPrices_thenHistoryServedByEodhdOnly() throws Exception {
        ProviderStubs.eodhdEod("AAPL.US", "2024-01-01:111.5", "2024-01-02:112.25");

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
                .body("find { it.priceDate == '2024-01-02' }.closePrice", equalTo(112.25f));

        ProviderStubs.verifyNoTwelveDataTraffic();
        assertEquals("eodhd", scalar(
                "select distinct provider from price_history where symbol = 'AAPL'"));
    }

    @Test
    @DataSet(value = "datasets/eodhd-barc-reference-seed.yml", cleanBefore = true,
            executeScriptsBefore = "datasets/transactions-barc-seed.sql")
    void givenGbpPosition_whenGetValuation_thenPricesAndFxBothTravelThroughEodhd() throws Exception {
        ProviderStubs.eodhdEod("BARC.LSE", "2024-01-01:250.0", "2024-01-02:252.5");
        ProviderStubs.eodhdEod("GBPUSD.FOREX", "2024-01-01:1.25", "2024-01-02:1.28");

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
                // 10 shares x (250 pence -> 2.50 GBP) x 1.25 and x (252.5 -> 2.525) x 1.28
                .body("[0].totalMarketValue", equalTo(31.25f))
                .body("[1].totalMarketValue", equalTo(32.32f));

        ProviderStubs.verifyNoTwelveDataTraffic();
        assertEquals("GBP|eodhd", scalar(
                "select distinct currency || '|' || provider from price_history where symbol = 'BARC'"));
        assertEquals("eodhd", scalar(
                "select distinct provider from fx_rate_history "
                        + "where base_currency = 'GBP' and quote_currency = 'USD'"));
    }

    @Test
    @DataSet(value = "datasets/eodhd-barc-reference-seed.yml", cleanBefore = true,
            executeScriptsBefore = "datasets/transactions-barc-seed.sql")
    void givenGbpPosition_whenGetPerformanceInputs_thenFlowsConvertThroughEodhdFx() {
        ProviderStubs.eodhdEod("BARC.LSE", "2024-01-01:250.0", "2024-01-02:252.5");
        ProviderStubs.eodhdEod("GBPUSD.FOREX", "2024-01-01:1.25", "2024-01-02:1.28");

        given()
                .header("X-User-Id", USER_A)
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/portfolio/performance-inputs")
                .then()
                .statusCode(200)
                .body("valuations", hasSize(2))
                .body("valuations[0].totalMarketValue", equalTo(31.25f))
                .body("capitalFlows.size()", greaterThanOrEqualTo(1))
                .body("incompleteDays", equalTo(0));

        ProviderStubs.verifyNoTwelveDataTraffic();
    }

    @Test
    @DataSet(value = "datasets/eodhd-barc-reference-seed.yml", cleanBefore = true,
            executeScriptsBefore = "datasets/transactions-barc-seed.sql")
    void givenGbpPosition_whenGetSummary_thenSpotAndFxSpotTravelThroughEodhd() throws Exception {
        ProviderStubs.eodhdRealTime("BARC.LSE", "250.0");
        ProviderStubs.eodhdRealTime("GBPUSD.FOREX", "1.28");

        given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/portfolio/summary")
                .then()
                .statusCode(200)
                .body("valuationCurrency", equalTo("USD"))
                .body("totalPositions", equalTo(1))
                // 10 shares x (250 pence -> 2.50 GBP) x 1.28
                .body("totalMarketValue", equalTo(32.0f));

        ProviderStubs.verifyNoTwelveDataTraffic();
        assertEquals("eodhd", scalar(
                "select provider from spot_quotes where kind = 'FX' and symbol = 'GBP/USD'"));
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenUnresolvableSymbol_whenGetPrices_thenSanitized422WithoutFallbackToAbsorbIt() throws Exception {
        // Default stubs: catalog resolves, search finds nothing -> SymbolResolution surfaces.
        given()
                .header("X-User-Id", USER_A)
                .queryParam("symbols", "ZZZZ")
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/prices")
                .then()
                .statusCode(422)
                .body("error", equalTo("MARKET_SYMBOL_UNRESOLVED"));

        assertEquals("0", scalar(
                "select count(*) from eodhd_symbol_mappings where canonical_symbol = 'ZZZZ'"));
    }

    @Test
    @DataSet(value = "datasets/eodhd-reference-seed.yml", cleanBefore = true)
    void givenEodhdRateLimited_whenGetPrices_thenReturns503WithRetryAfter() {
        ProviderStubs.eodhdEodRateLimited("TSLA.US", 30);

        given()
                .header("X-User-Id", USER_A)
                .queryParam("symbols", "TSLA")
                .queryParam("from", "2024-01-01")
                .queryParam("to", "2024-01-02")
                .when()
                .get("/api/daily-history/prices")
                .then()
                .statusCode(503)
                .header("Retry-After", notNullValue())
                .body("error", equalTo("RATE_LIMITED"));
    }

    @Test
    @DataSet(value = "datasets/eodhd-reference-seed.yml", cleanBefore = true,
            executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenIngestionRun_whenTriggered_thenCompletesWithEodhdProvenance() throws Exception {
        ProviderStubs.eodhdEod("AAPL.US", "2024-01-01:111.5", "2024-01-02:112.25");

        String runId = given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "from", "2024-01-01",
                        "to", "2024-01-02",
                        "ticker", "AAPL",
                        "includeBackfill", false))
                .when()
                .post("/api/price-ingestion/runs")
                .then()
                .statusCode(202)
                .body("runId", notNullValue())
                .extract()
                .path("runId");

        assertTrue(waitUntil(() -> "COMPLETED".equals(given()
                .header("X-User-Id", USER_A)
                .when()
                .get("/api/price-ingestion/runs/latest")
                .then()
                .statusCode(200)
                .body("id", equalTo(runId))
                .extract()
                .path("status")), Duration.ofSeconds(15)));

        ProviderStubs.verifyNoTwelveDataTraffic();
        assertEquals("eodhd", scalar(
                "select distinct provider from price_history where symbol = 'AAPL'"));
    }

    private static boolean waitUntil(Supplier<Boolean> condition, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = CDI.current().select(AgroalDataSource.class).get().getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }
}

package com.portfolio.integration.rest;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.integration.IntegrationTestProfile;
import com.portfolio.integration.ProviderStubs;
import com.portfolio.integration.WireMockMarketDataResource;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Exact daily-history construction over one complex month, entirely from the store (both
 * providers are proven untouched): AAPL accumulates, pays a dividend, and splits 4:1 while
 * BARC (GBP) opens early, loses its quote feed for a week, and exits on the last day. Every
 * endpoint of the daily-history controller is asserted value by value, because these series
 * are the inputs to quant analysis — a silently wrong number here poisons everything above it.
 *
 * <p>Fixtures: {@code complex-ledger-seed.sql} + {@code complex-market-data-seed.sql}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class DailyHistoryQuantScenarioIT {

    private static final String USER_A = "user-a";
    private static final String FROM = "2024-01-02";
    private static final String TO = "2024-01-19";

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
            "datasets/complex-ledger-seed.sql",
            "datasets/complex-market-data-seed.sql"
    })
    void givenComplexLedger_whenGetPositions_thenEveryTradeDayCarriesExactState() {
        ValidatableResponse response = get("/api/daily-history/positions")
                .body("$", hasSize(5));

        // Sparse rows: one per ticker per trade day, dividend day absent, sorted by date.
        assertSnapshot(response, 0, "2024-01-02", "AAPL", 10.0f, 100.5f, 1005.0f);
        assertSnapshot(response, 1, "2024-01-03", "BARC", 10.0f, 2.5f, 25.0f);
        assertSnapshot(response, 2, "2024-01-10", "AAPL", 20.0f, 125.5f, 2510.0f);
        // 4:1 split: shares x4, average /4, invested unchanged.
        assertSnapshot(response, 3, "2024-01-15", "AAPL", 80.0f, 31.375f, 2510.0f);
        // Full exit: the zero-share row is emitted so the exit itself is visible.
        assertSnapshot(response, 4, "2024-01-19", "BARC", 0.0f, 0.0f, 0.0f);
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/complex-ledger-seed.sql",
            "datasets/complex-market-data-seed.sql"
    })
    void givenComplexLedger_whenGetPositionsForTicker_thenOnlyThatLedgerAppears() {
        given()
                .header("X-User-Id", USER_A)
                .queryParam("from", FROM)
                .queryParam("to", TO)
                .queryParam("ticker", "AAPL")
                .when()
                .get("/api/daily-history/positions")
                .then()
                .statusCode(200)
                .body("$", hasSize(3))
                .body("ticker", equalTo(List.of("AAPL", "AAPL", "AAPL")));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/complex-ledger-seed.sql",
            "datasets/complex-market-data-seed.sql"
    })
    void givenSeededSeries_whenGetPricesForBothSymbols_thenReturnsBothStoredSeries() {
        get("/api/daily-history/prices", "symbols", "AAPL,BARC")
                .body("$", hasSize(21))
                .body("findAll { it.symbol == 'AAPL' }", hasSize(14))
                .body("findAll { it.symbol == 'BARC' }", hasSize(7))
                .body("find { it.symbol == 'AAPL' && it.priceDate == '2024-01-15' }.closePrice",
                        equalTo(30.0f))
                .body("find { it.symbol == 'BARC' && it.priceDate == '2024-01-19' }.currency",
                        equalTo("GBP"));

        ProviderStubs.verifyNoTwelveDataTraffic();
        ProviderStubs.verifyNoEodhdTraffic();
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/complex-ledger-seed.sql",
            "datasets/complex-market-data-seed.sql"
    })
    void givenComplexLedger_whenGetValuation_thenDayByDaySeriesIsExact() {
        ValidatableResponse response = get("/api/daily-history/portfolio/valuation")
                .body("$", hasSize(18))
                .body("findAll { it.complete == false }", hasSize(5));

        // AAPL alone before BARC opens.
        assertValuation(response, "2024-01-02", 1000.0f, true);
        assertValuation(response, "2024-01-03", 1051.25f, true);
        // Weekend: both price and FX floor to Friday's values.
        assertValuation(response, "2024-01-06", 1092.5f, true);
        assertValuation(response, "2024-01-07", 1092.5f, true);
        // Monday: fresh AAPL price, BARC still floored (2.60 x 1.26).
        assertValuation(response, "2024-01-08", 1132.76f, true);
        // AAPL doubles up intraday-of-record: end-of-day snapshot holds 20 shares.
        assertValuation(response, "2024-01-10", 2312.76f, true);
        // BARC's quote is now older than the five-day staleness: its market value drops out
        // and the day is incomplete, but its cost basis (25 GBP x 1.26) stays in.
        assertValuation(response, "2024-01-11", 2320.0f, false);
        response.body("find { it.date == '2024-01-11' }.totalCost", equalTo(2541.5f));
        // Split day: 80 shares x 30 keep market value continuous with 20 x 120.
        assertValuation(response, "2024-01-14", 2400.0f, false);
        assertValuation(response, "2024-01-15", 2400.0f, false);
        // BARC quotes resume.
        assertValuation(response, "2024-01-16", 2515.84f, true);
        assertValuation(response, "2024-01-18", 2677.12f, true);
        // Exit day: BARC's zero-share snapshot no longer participates; AAPL only, complete.
        assertValuation(response, "2024-01-19", 2720.0f, true);

        ProviderStubs.verifyNoTwelveDataTraffic();
        ProviderStubs.verifyNoEodhdTraffic();
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/complex-ledger-seed.sql",
            "datasets/complex-market-data-seed.sql"
    })
    void givenComplexLedger_whenGetCapitalFlows_thenLedgerProjectsExactSignedFlows() {
        get("/api/daily-history/portfolio/capital-flows")
                .body("flows", hasSize(5))
                .body("flows.findAll { it.kind == 'SPLIT' }", hasSize(0))
                .body("flows.find { it.flowDate == '2024-01-02' }.amount", equalTo(-1005.0f))
                .body("flows.find { it.flowDate == '2024-01-02' }.fees", equalTo(5.0f))
                .body("flows.find { it.flowDate == '2024-01-03' }.amount", equalTo(-25.0f))
                .body("flows.find { it.flowDate == '2024-01-03' }.currency", equalTo("GBP"))
                .body("flows.find { it.flowDate == '2024-01-10' }.amount", equalTo(-1505.0f))
                .body("flows.find { it.kind == 'DIVIDEND' }.amount", equalTo(10.0f))
                // Sell nets out its fee: 10 x 3 - 1.
                .body("flows.find { it.flowDate == '2024-01-19' }.amount", equalTo(29.0f))
                .body("flows.find { it.flowDate == '2024-01-19' }.fees", equalTo(1.0f));
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/complex-ledger-seed.sql",
            "datasets/complex-market-data-seed.sql"
    })
    void givenComplexLedger_whenGetPerformanceInputs_thenBundleConvertsFlowsAtFlowDateRates() {
        get("/api/daily-history/portfolio/performance-inputs")
                .body("valuations", hasSize(18))
                .body("incompleteDays", equalTo(5))
                .body("capitalFlows", hasSize(5))
                // GBP flows convert at their own date's rate: -25 x 1.25 and (30 - 1) x 1.30.
                .body("capitalFlows.find { it.flowDate == '2024-01-03' }.amount", equalTo(-31.25f))
                .body("capitalFlows.find { it.flowDate == '2024-01-03' }.currency", equalTo("USD"))
                .body("capitalFlows.find { it.flowDate == '2024-01-19' }.amount", equalTo(37.7f))
                .body("capitalFlows.find { it.flowDate == '2024-01-19' }.currency", equalTo("USD"))
                // Base-currency flows pass through untouched.
                .body("capitalFlows.find { it.flowDate == '2024-01-02' }.amount", equalTo(-1005.0f));

        ProviderStubs.verifyNoTwelveDataTraffic();
        ProviderStubs.verifyNoEodhdTraffic();
    }

    @Test
    void givenMissingDates_whenAnyDailyHistoryEndpoint_thenBadRequest() {
        for (String path : List.of(
                "/api/daily-history/positions",
                "/api/daily-history/prices",
                "/api/daily-history/portfolio/valuation",
                "/api/daily-history/portfolio/capital-flows",
                "/api/daily-history/portfolio/performance-inputs")) {
            given()
                    .header("X-User-Id", USER_A)
                    .queryParam("from", FROM)
                    .when()
                    .get(path)
                    .then()
                    .statusCode(400);
        }
    }

    private ValidatableResponse get(String path, String... extraParams) {
        var request = given()
                .header("X-User-Id", USER_A)
                .queryParam("from", FROM)
                .queryParam("to", TO);
        for (int i = 0; i < extraParams.length; i += 2) {
            request = request.queryParam(extraParams[i], extraParams[i + 1]);
        }
        return request.when().get(path).then().statusCode(200);
    }

    private static void assertSnapshot(
            ValidatableResponse response, int index, String date, String ticker,
            float shares, float avgCost, float invested) {
        response
                .body("[" + index + "].snapshotDate", equalTo(date))
                .body("[" + index + "].ticker", equalTo(ticker))
                .body("[" + index + "].sharesOwned", num(shares))
                .body("[" + index + "].averageCostPerShare", num(avgCost))
                .body("[" + index + "].totalInvestedAmount", num(invested));
    }

    /** BigDecimal zero serializes as JSON integer 0; whole values must match either shape. */
    private static org.hamcrest.Matcher<?> num(float value) {
        return value == Math.rint(value)
                ? org.hamcrest.Matchers.anyOf(equalTo(value), equalTo((int) value))
                : equalTo(value);
    }

    private static void assertValuation(
            ValidatableResponse response, String date, float marketValue, boolean complete) {
        response
                .body("find { it.date == '" + date + "' }.totalMarketValue", equalTo(marketValue))
                .body("find { it.date == '" + date + "' }.complete", equalTo(complete));
    }
}

package com.portfolio.integration.mcp;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.portfolio.integration.IntegrationTestProfile;
import com.portfolio.integration.WireMockMarketDataResource;
import io.agroal.api.AgroalDataSource;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.MultiMap;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each step is its own {@code .thenAssertResults()} call: chaining multiple
 * {@code toolsCall(...)} inside one batch dispatches them concurrently rather than in
 * declaration order, which both breaks dependent flows and races the reactive DB session
 * even for independent reads.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class McpPortfolioAndHistoryIT {

    private static final String USER_A = "user-a";

    @SuppressWarnings("unused")
    private final ConnectionHolder connectionHolder =
            () -> CDI.current().select(AgroalDataSource.class).get().getConnection();

    @BeforeEach
    void resetWireMock() {
        WireMockMarketDataResource.server.resetAll();
        WireMockMarketDataResource.stubDefaults();
    }

    @AfterEach
    void restoreDefaultStubs() {
        WireMockMarketDataResource.server.resetAll();
        WireMockMarketDataResource.stubDefaults();
    }

    private static McpAssured.McpStreamableTestClient connectedClientFor(String userId) {
        return McpAssured.newStreamableClient()
                .setAdditionalHeaders(request -> MultiMap.caseInsensitiveMultiMap().add("X-User-Id", userId))
                .build()
                .connect();
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededLedger_whenListPositionsAndSummary_thenDerivedFromLedger() {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/price"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"price\":\"190.5\"}")));

        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("list_positions", Map.of(), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"count\":2"));
                    })
                    .thenAssertResults();

            client.when()
                    .toolsCall("get_position", Map.of("ticker", "AAPL"), response -> {
                        assertFalse(response.isError());
                        String json = response.firstContent().asText().text();
                        assertTrue(json.contains("\"ticker\":\"AAPL\""));
                        assertTrue(json.contains("\"isActive\":true"));
                    })
                    .thenAssertResults();

            client.when()
                    .toolsCall("get_portfolio_summary", Map.of(), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"totalPositions\":2"));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenMissingTicker_whenGetPosition_thenNotFound() {
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("get_position", Map.of("ticker", "UNKNOWN"), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text().startsWith("NOT_FOUND"));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenManualPrice_whenSetAndClearPositionPrice_thenPriceReflectsOverrideThenReverts() {
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("set_position_price", Map.of("ticker", "AAPL", "price", 250, "currency", "USD"),
                            response -> {
                                assertFalse(response.isError());
                                assertTrue(response.firstContent().asText().text().contains("\"currentPrice\":250"));
                            })
                    .thenAssertResults();

            client.when()
                    .toolsCall("clear_position_price", Map.of("ticker", "AAPL"), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"ticker\":\"AAPL\""));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/market-data-seed.sql"
    })
    void givenSeededHistory_whenGetDailyPositionHistory_thenReturnsSnapshots() {
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("get_daily_position_history",
                            Map.of("from", "2024-01-01", "to", "2024-01-05"), response -> {
                                assertFalse(response.isError());
                                assertTrue(response.firstContent().asText().text().contains("\"ticker\":\"AAPL\""));
                            })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/market-data-seed.sql"
    })
    void givenSeededPrices_whenGetPriceHistory_thenReturnsEntries() {
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("get_price_history",
                            Map.of("symbols", List.of("AAPL"), "from", "2024-01-01", "to", "2024-01-02"),
                            response -> {
                                assertFalse(response.isError());
                                String json = response.firstContent().asText().text();
                                assertTrue(json.contains("\"closePrice\":100"));
                                assertTrue(json.contains("\"closePrice\":101"));
                            })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/market-data-seed.sql"
    })
    void givenSeededData_whenGetPortfolioValuationHistory_thenReturnsValuations() {
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("get_portfolio_valuation_history",
                            Map.of("from", "2024-01-01", "to", "2024-01-02"), response -> {
                                assertFalse(response.isError());
                                assertTrue(response.firstContent().asText().text().contains("\"valuationCurrency\":\"USD\""));
                            })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/market-data-seed.sql"
    })
    void givenSeededLedger_whenGetCapitalFlows_thenReturnsFlows() {
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("get_capital_flows",
                            Map.of("from", "2024-01-01", "to", "2024-01-05"), response -> {
                                assertFalse(response.isError());
                                String json = response.firstContent().asText().text();
                                assertTrue(json.contains("\"ticker\":\"AAPL\""));
                                assertTrue(json.contains("\"ticker\":\"MSFT\""));
                            })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = {
            "datasets/transactions-seed.sql",
            "datasets/market-data-seed.sql"
    })
    void givenSeededData_whenGetPerformanceInputs_thenReturnsBundle() {
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("get_performance_inputs",
                            Map.of("from", "2024-01-01", "to", "2024-01-02"), response -> {
                                assertFalse(response.isError());
                                String json = response.firstContent().asText().text();
                                assertTrue(json.contains("\"valuations\""));
                                assertTrue(json.contains("\"capitalFlows\""));
                            })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenMissingFromDate_whenGetDailyPositionHistory_thenInvalidRequest() {
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("get_daily_position_history", Map.of("to", "2024-01-02"), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text().startsWith("INVALID_REQUEST"));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenProviderRateLimit_whenGetPriceHistory_thenFailsWithRateLimitedCode() {
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/time_series"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "code": 429,
                                  "message": "You have run out of API credits for the current minute.",
                                  "status": "error"
                                }
                                """)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/exchanges-list/"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"Name":"USA Stocks","Code":"US","Country":"USA",
                                  "Currency":"USD","CountryISO2":"US","CountryISO3":"USA"}]
                                """)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/search/TSLA"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"Code":"TSLA","Exchange":"US","Country":"USA","Currency":"USD"}]
                                """)));
        WireMockMarketDataResource.server.stubFor(WireMock.get(urlPathEqualTo("/api/eod/TSLA.US"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "30")
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("get_price_history",
                            Map.of("symbols", List.of("TSLA"), "from", "2024-01-01", "to", "2024-01-02"),
                            response -> {
                                assertTrue(response.isError());
                                assertTrue(response.firstContent().asText().text().startsWith("RATE_LIMITED"));
                            })
                    .thenAssertResults();
        }
    }
}

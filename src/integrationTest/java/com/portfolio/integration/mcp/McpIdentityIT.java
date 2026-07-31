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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class McpIdentityIT {

    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";

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

    private static McpAssured.McpStreamableTestClient connectedClientFor(String userId) {
        return McpAssured.newStreamableClient()
                .setAdditionalHeaders(request -> MultiMap.caseInsensitiveMultiMap().add("X-User-Id", userId))
                .build()
                .connect();
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenNoUserIdHeader_whenCallingReadTool_thenFailsWithMissingUserIdCode() {
        try (McpAssured.McpStreamableTestClient client = McpAssured.newConnectedStreamableClient()) {
            client.when()
                    .toolsCall("list_positions", Map.of(), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text().startsWith("MISSING_USER_ID"));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenNoUserIdHeader_whenCallingMutatingTool_thenFailsWithMissingUserIdCode() {
        try (McpAssured.McpStreamableTestClient client = McpAssured.newConnectedStreamableClient()) {
            client.when()
                    .toolsCall("trigger_price_ingestion", Map.of(), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text().startsWith("MISSING_USER_ID"));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenTwoUsers_whenListPositions_thenEachSeesOnlyOwnHoldings() {
        try (McpAssured.McpStreamableTestClient clientA = connectedClientFor(USER_A);
                McpAssured.McpStreamableTestClient clientB = connectedClientFor(USER_B)) {
            clientA.when()
                    .toolsCall("list_positions", Map.of(), response -> {
                        assertFalse(response.isError());
                        String json = response.firstContent().asText().text();
                        assertTrue(json.contains("\"count\":2"));
                        assertTrue(json.contains("AAPL"));
                        assertTrue(json.contains("MSFT"));
                    })
                    .thenAssertResults();

            clientB.when()
                    .toolsCall("list_positions", Map.of(), response -> {
                        assertFalse(response.isError());
                        String json = response.firstContent().asText().text();
                        assertTrue(json.contains("\"count\":1"));
                        assertTrue(json.contains("GOOG"));
                        assertFalse(json.contains("AAPL"));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenTwoUsers_whenGetPortfolioSummary_thenEachSeesOwnTotals() {
        try (McpAssured.McpStreamableTestClient clientA = connectedClientFor(USER_A);
                McpAssured.McpStreamableTestClient clientB = connectedClientFor(USER_B)) {
            clientA.when()
                    .toolsCall("get_portfolio_summary", Map.of(), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"totalPositions\":2"));
                    })
                    .thenAssertResults();

            clientB.when()
                    .toolsCall("get_portfolio_summary", Map.of(), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"totalPositions\":1"));
                    })
                    .thenAssertResults();
        }
    }
}

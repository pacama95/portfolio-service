package com.portfolio.integration.mcp;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.integration.IntegrationTestProfile;
import io.agroal.api.AgroalDataSource;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.MultiMap;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each dependent step is its own {@code .thenAssertResults()} call: chaining multiple
 * {@code toolsCall(...)} inside one batch dispatches them concurrently rather than in
 * declaration order, which breaks flows where a later step depends on an earlier step's
 * side effect (and races the reactive DB session even for independent reads).
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class McpTransactionFlowIT {

    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";
    private static final String AAPL_BUY_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String AAPL_SELL_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @SuppressWarnings("unused")
    private final ConnectionHolder connectionHolder =
            () -> CDI.current().select(AgroalDataSource.class).get().getConnection();

    private static McpAssured.McpStreamableTestClient connectedClientFor(String userId) {
        return McpAssured.newStreamableClient()
                .setAdditionalHeaders(request -> MultiMap.caseInsensitiveMultiMap().add("X-User-Id", userId))
                .build()
                .connect();
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenMissingUserHeader_whenCreateTransaction_thenMissingUserIdErrorAndSessionStaysUsable() {
        try (McpAssured.McpStreamableTestClient client = McpAssured.newConnectedStreamableClient()) {
            client.when()
                    .toolsCall("create_transaction", Map.of(
                            "ticker", "AAPL",
                            "transactionType", "BUY",
                            "assetType", "COMMON_STOCK",
                            "quantity", 10,
                            "price", 100,
                            "currency", "USD",
                            "transactionDate", "2024-01-01",
                            "exchange", "NASDAQ",
                            "country", "US"), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text().startsWith("MISSING_USER_ID"));
                    })
                    .thenAssertResults();

            // The session must still be usable after a rejection.
            client.when()
                    .toolsList(page -> assertEquals(18, page.size()))
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenValidRequest_whenCreateAndGetTransaction_thenScopedToUser() {
        try (McpAssured.McpStreamableTestClient clientA = connectedClientFor(USER_A);
                McpAssured.McpStreamableTestClient clientB = connectedClientFor(USER_B)) {
            AtomicReference<String> createdId = new AtomicReference<>();

            clientA.when()
                    .toolsCall("create_transaction", Map.of(
                            "ticker", "AAPL",
                            "transactionType", "BUY",
                            "assetType", "COMMON_STOCK",
                            "quantity", 10,
                            "price", 100,
                            "currency", "USD",
                            "transactionDate", "2024-01-01",
                            "exchange", "NASDAQ",
                            "country", "US",
                            "companyName", "Apple"), response -> {
                        assertFalse(response.isError());
                        String json = response.firstContent().asText().text();
                        assertTrue(json.contains("\"ticker\":\"AAPL\""));
                        createdId.set(json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
                    })
                    .thenAssertResults();

            clientA.when()
                    .toolsCall("get_transaction", Map.of("id", createdId.get()), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"ticker\":\"AAPL\""));
                    })
                    .thenAssertResults();

            clientB.when()
                    .toolsCall("get_transaction", Map.of("id", createdId.get()), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text().startsWith("NOT_FOUND"));
                    })
                    .thenAssertResults();

            clientB.when()
                    .toolsCall("search_transactions", Map.of(), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"count\":0"));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenInvalidQuantity_whenCreateTransaction_thenValidationErrorListsFieldAndDbUnchanged() {
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("create_transaction", Map.of(
                            "ticker", "AAPL",
                            "transactionType", "BUY",
                            "assetType", "COMMON_STOCK",
                            "quantity", -1,
                            "price", 100,
                            "currency", "USD",
                            "transactionDate", "2024-01-01",
                            "exchange", "NASDAQ",
                            "country", "US"), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("quantity"));
                    })
                    .thenAssertResults();

            client.when()
                    .toolsCall("count_transactions", Map.of(), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"count\":0"));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenSeededTransactions_whenSearchUpdateCountDelete_thenFlowWorks() {
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("search_transactions", Map.of("ticker", "AAPL"), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"count\":2"));
                    })
                    .thenAssertResults();

            client.when()
                    .toolsCall("update_transaction", Map.of("id", AAPL_BUY_ID, "notes", "updated via mcp"),
                            response -> {
                                assertFalse(response.isError());
                                assertTrue(response.firstContent().asText().text().contains("updated via mcp"));
                            })
                    .thenAssertResults();

            client.when()
                    .toolsCall("count_transactions", Map.of(), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"count\":3"));
                    })
                    .thenAssertResults();

            // The seeded SELL has no downstream dependents, so deleting it never oversells.
            client.when()
                    .toolsCall("delete_transaction", Map.of("id", AAPL_SELL_ID), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"deleted\":true"));
                    })
                    .thenAssertResults();

            client.when()
                    .toolsCall("get_transaction", Map.of("id", AAPL_SELL_ID), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text().startsWith("NOT_FOUND"));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenDeleteWouldOversellRemainingLedger_whenDeleteTransaction_thenConflictAndRowUntouched() {
        // Deleting the BUY leaves the seeded SELL (2 shares) with nothing to sell.
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("delete_transaction", Map.of("id", AAPL_BUY_ID), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text().startsWith("CONFLICT"));
                    })
                    .thenAssertResults();

            client.when()
                    .toolsCall("get_transaction", Map.of("id", AAPL_BUY_ID), response -> {
                        assertFalse(response.isError());
                        assertTrue(response.firstContent().asText().text().contains("\"ticker\":\"AAPL\""));
                    })
                    .thenAssertResults();
        }
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenMissingTransaction_whenGetTransaction_thenNotFound() {
        try (McpAssured.McpStreamableTestClient client = connectedClientFor(USER_A)) {
            client.when()
                    .toolsCall("get_transaction", Map.of("id", UUID.randomUUID().toString()), response -> {
                        assertTrue(response.isError());
                        assertTrue(response.firstContent().asText().text().startsWith("NOT_FOUND"));
                    })
                    .thenAssertResults();
        }
    }
}

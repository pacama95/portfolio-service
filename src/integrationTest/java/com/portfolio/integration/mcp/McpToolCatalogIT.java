package com.portfolio.integration.mcp;

import com.portfolio.integration.IntegrationTestProfile;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
class McpToolCatalogIT {

    private static final int EXPECTED_TOOL_COUNT = 18;

    @Test
    void givenServer_whenToolsList_thenExposesEighteenSelfDescribingTools() {
        try (McpAssured.McpStreamableTestClient client = McpAssured.newConnectedStreamableClient()) {
            client.when()
                    .toolsList(page -> {
                        assertEquals(EXPECTED_TOOL_COUNT, page.size());
                        for (McpAssured.ToolInfo tool : page.tools()) {
                            assertNotNull(tool.name());
                            assertNotNull(tool.description(), tool.name() + " has no description");
                            assertFalse(tool.description().isBlank(), tool.name() + " has a blank description");
                            assertNotNull(tool.inputSchema(), tool.name() + " has no input schema");
                            assertNotNull(tool.outputSchema(), tool.name() + " has no output schema");

                            JsonObject properties = tool.inputSchema().getJsonObject("properties");
                            if (properties != null) {
                                for (String argName : properties.fieldNames()) {
                                    JsonObject arg = properties.getJsonObject(argName);
                                    String description = arg.getString("description");
                                    assertNotNull(description,
                                            tool.name() + "." + argName + " has no description");
                                    assertFalse(description.isBlank(),
                                            tool.name() + "." + argName + " has a blank description");
                                }
                            }
                        }
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void givenServer_whenToolsList_thenIncludesEveryExpectedToolName() {
        try (McpAssured.McpStreamableTestClient client = McpAssured.newConnectedStreamableClient()) {
            client.when()
                    .toolsList(page -> {
                        assertNotNull(page.findByName("create_transaction"));
                        assertNotNull(page.findByName("get_transaction"));
                        assertNotNull(page.findByName("search_transactions"));
                        assertNotNull(page.findByName("update_transaction"));
                        assertNotNull(page.findByName("delete_transaction"));
                        assertNotNull(page.findByName("count_transactions"));
                        assertNotNull(page.findByName("list_positions"));
                        assertNotNull(page.findByName("get_position"));
                        assertNotNull(page.findByName("set_position_price"));
                        assertNotNull(page.findByName("clear_position_price"));
                        assertNotNull(page.findByName("get_portfolio_summary"));
                        assertNotNull(page.findByName("get_daily_position_history"));
                        assertNotNull(page.findByName("get_price_history"));
                        assertNotNull(page.findByName("get_portfolio_valuation_history"));
                        assertNotNull(page.findByName("get_capital_flows"));
                        assertNotNull(page.findByName("get_performance_inputs"));
                        assertNotNull(page.findByName("trigger_price_ingestion"));
                        assertNotNull(page.findByName("get_price_ingestion_run"));
                    })
                    .thenAssertResults();
        }
    }
}

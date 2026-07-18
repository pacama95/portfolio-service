package com.portfolio.integration;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;
import java.util.Map;

public class IntegrationTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("quarkus.datasource.devservices.enabled", "false"),
                Map.entry("quarkus.hibernate-orm.database.generation", "none"),
                Map.entry("quarkus.liquibase.migrate-at-start", "true"),
                Map.entry("quarkus.hibernate-orm.log.sql", "false"),
                Map.entry("quarkus.scheduler.enabled", "false"),
                Map.entry("quarkus.http.host", "localhost"),
                Map.entry("quarkus.http.test-port", "18087"),
                Map.entry("quarkus.http.test-host", "localhost"),
                Map.entry("application.market-data.twelve-data.api-key", "test-api-key"),
                Map.entry("application.portfolio.base-currency", "USD"),
                Map.entry("application.market-data.spot-price-freshness", "PT15M"),
                Map.entry("application.market-data.fx-freshness", "PT1H")
        );
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(
                new TestResourceEntry(PostgresTestResource.class),
                new TestResourceEntry(WireMockMarketDataResource.class)
        );
    }
}

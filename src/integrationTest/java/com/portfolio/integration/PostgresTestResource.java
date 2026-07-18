package com.portfolio.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    private PostgreSQLContainer<?> postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("portfolio_service_db")
                .withUsername("postgres")
                .withPassword("test");
        postgres.start();
        return Map.of(
                "quarkus.datasource.username", postgres.getUsername(),
                "quarkus.datasource.password", postgres.getPassword(),
                "quarkus.datasource.reactive.url",
                "postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName(),
                "quarkus.datasource.jdbc.url", postgres.getJdbcUrl(),
                "quarkus.hibernate-orm.enabled", "true",
                "quarkus.liquibase.migrate-at-start", "true",
                "quarkus.datasource.devservices.enabled", "false");
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }
}

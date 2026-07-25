package com.portfolio.integration.rest;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.portfolio.integration.IntegrationTestProfile;
import com.portfolio.integration.WireMockMarketDataResource;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class RateLimitApiIT {

    private static final String USER_A = "user-a";

    @SuppressWarnings("unused")
    private final ConnectionHolder connectionHolder =
            () -> CDI.current().select(AgroalDataSource.class).get().getConnection();

    @BeforeEach
    void stubRateLimitedTimeSeries() {
        WireMockMarketDataResource.server.resetAll();
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
    }

    @AfterEach
    void restoreDefaultStubs() {
        WireMockMarketDataResource.server.resetAll();
        WireMockMarketDataResource.stubDefaults();
    }

    @Test
    @DataSet(cleanBefore = true)
    void givenProviderRateLimit_whenGetPrices_thenReturns503WithRetryAfter() {
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
                .body("error", equalTo("RATE_LIMITED"))
                .body("message", notNullValue());
    }
}

package com.portfolio.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

public class WireMockMarketDataResource implements QuarkusTestResourceLifecycleManager {

    public static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        stubDefaults();
        return Map.of(
                "quarkus.rest-client.twelve-data-api.url", server.baseUrl(),
                "application.market-data.twelve-data.api-key", "test-key");
    }

    public static void stubDefaults() {
        server.stubFor(WireMock.get(urlPathEqualTo("/price"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"price\":\"100.0\"}")));
        server.stubFor(WireMock.get(urlPathEqualTo("/exchange_rate"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"rate\":\"1.1\"}")));
        server.stubFor(WireMock.get(urlPathEqualTo("/time_series"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "meta": {"currency": "USD"},
                                  "values": [
                                    {"datetime": "2024-01-02 00:00:00", "close": "101.0"},
                                    {"datetime": "2024-01-01 00:00:00", "close": "100.0"}
                                  ]
                                }
                                """)));
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}

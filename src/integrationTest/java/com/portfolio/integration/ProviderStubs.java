package com.portfolio.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * Provider-level vocabulary for end-to-end tests: stub whole providers up or down, stub one
 * symbol's data on one provider, and verify which provider a request actually travelled through.
 * All stubs are symbol-scoped and registered at priority 1 so they beat the broad defaults from
 * {@link WireMockMarketDataResource#stubDefaults()}.
 *
 * <p>TwelveData paths: {@code /price}, {@code /exchange_rate}, {@code /time_series}.
 * EODHD paths: everything under {@code /api/}. Series bars are given as {@code "date:close"}.
 */
public final class ProviderStubs {

    private static final List<String> TWELVE_DATA_PATHS =
            List.of("/price", "/exchange_rate", "/time_series");

    private ProviderStubs() {
    }

    // --- whole-provider switches ---

    /** Every TwelveData endpoint answers HTTP 500 — a typed, fallback-eligible outage. */
    public static void twelveDataDown() {
        for (String path : TWELVE_DATA_PATHS) {
            server().stubFor(WireMock.get(urlPathEqualTo(path))
                    .atPriority(1)
                    .willReturn(aResponse().withStatus(500)));
        }
    }

    /** Every EODHD endpoint answers HTTP 500 — a typed, fallback-eligible outage. */
    public static void eodhdDown() {
        server().stubFor(WireMock.get(urlPathMatching("/api/.*"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)));
    }

    /**
     * Every TwelveData endpoint reports its credit limit the way the real API does:
     * HTTP 200 with an error body. Applies to all operations — every response DTO carries
     * {@code code}/{@code status} and goes through the same error check.
     */
    public static void twelveDataRateLimited() {
        for (String path : TWELVE_DATA_PATHS) {
            server().stubFor(WireMock.get(urlPathEqualTo(path))
                    .atPriority(1)
                    .willReturn(okJson("{\"code\":429,"
                            + "\"message\":\"You have run out of API credits for the current minute.\","
                            + "\"status\":\"error\"}")));
        }
    }

    // --- TwelveData, per symbol ---

    public static void twelveDataSpot(String symbol, String price) {
        server().stubFor(WireMock.get(urlPathEqualTo("/price"))
                .atPriority(1)
                .withQueryParam("symbol", equalTo(symbol))
                .willReturn(okJson("{\"price\":\"" + price + "\"}")));
    }

    public static void twelveDataFxRate(String pair, String rate) {
        server().stubFor(WireMock.get(urlPathEqualTo("/exchange_rate"))
                .atPriority(1)
                .withQueryParam("symbol", equalTo(pair))
                .willReturn(okJson("{\"rate\":\"" + rate + "\"}")));
    }

    /** Stubs {@code /time_series} for one symbol; also used for FX pairs like {@code GBP/USD}. */
    public static void twelveDataSeries(String symbol, String currency, String... dateCloses) {
        String values = Stream.of(dateCloses)
                .map(ProviderStubs::split)
                .map(bar -> "{\"datetime\":\"" + bar[0] + " 00:00:00\",\"close\":\"" + bar[1] + "\"}")
                .collect(Collectors.joining(","));
        server().stubFor(WireMock.get(urlPathEqualTo("/time_series"))
                .atPriority(1)
                .withQueryParam("symbol", equalTo(symbol))
                .willReturn(okJson("{\"meta\":{\"currency\":\"" + currency + "\"},"
                        + "\"values\":[" + values + "]}")));
    }

    // --- EODHD, per symbol ---

    public static void eodhdRealTime(String qualifiedSymbol, String close) {
        server().stubFor(WireMock.get(urlPathEqualTo("/api/real-time/" + qualifiedSymbol))
                .atPriority(1)
                .willReturn(okJson("{\"close\":" + close + "}")));
    }

    public static void eodhdRealTimeDown(String qualifiedSymbol) {
        server().stubFor(WireMock.get(urlPathEqualTo("/api/real-time/" + qualifiedSymbol))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)));
    }

    public static void eodhdEod(String qualifiedSymbol, String... dateCloses) {
        String bars = Stream.of(dateCloses)
                .map(ProviderStubs::split)
                .map(bar -> "{\"date\":\"" + bar[0] + "\",\"close\":" + bar[1]
                        + ",\"adjusted_close\":" + bar[1] + "}")
                .collect(Collectors.joining(","));
        server().stubFor(WireMock.get(urlPathEqualTo("/api/eod/" + qualifiedSymbol))
                .atPriority(1)
                .willReturn(okJson("[" + bars + "]")));
    }

    public static void eodhdEodRateLimited(String qualifiedSymbol, int retryAfterSeconds) {
        server().stubFor(WireMock.get(urlPathEqualTo("/api/eod/" + qualifiedSymbol))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", String.valueOf(retryAfterSeconds))
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));
    }

    /** One exact search hit, enough for discovery to resolve {@code TICKER.EXCHANGE}. */
    public static void eodhdSearchHit(String ticker, String exchange, String country, String currency) {
        server().stubFor(WireMock.get(urlPathEqualTo("/api/search/" + ticker))
                .atPriority(1)
                .willReturn(okJson("[{\"Code\":\"" + ticker + "\",\"Exchange\":\"" + exchange
                        + "\",\"Country\":\"" + country + "\",\"Currency\":\"" + currency + "\"}]")));
    }

    // --- traffic assertions ---

    /** Proves a request was served without TwelveData: none of its endpoints saw traffic. */
    public static void verifyNoTwelveDataTraffic() {
        for (String path : TWELVE_DATA_PATHS) {
            server().verify(0, getRequestedFor(urlPathEqualTo(path)));
        }
    }

    /** Proves a request was served without EODHD: WireMock only hosts providers, so any {@code /api/*} hit would be EODHD. */
    public static void verifyNoEodhdTraffic() {
        server().verify(0, getRequestedFor(urlPathMatching("/api/.*")));
    }

    private static String[] split(String dateClose) {
        String[] parts = dateClose.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected 'date:close', got: " + dateClose);
        }
        return parts;
    }

    private static WireMockServer server() {
        return WireMockMarketDataResource.server;
    }
}

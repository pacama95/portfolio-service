package com.portfolio.integration.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.integration.IntegrationTestProfile;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.stream.StreamCommands;
import io.quarkus.redis.datasource.stream.StreamMessage;
import io.quarkus.redis.datasource.stream.StreamRange;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
class TransactionCreatedEventIT {

    private static final String STREAM = "transaction:created";
    private static final String USER_A = "user-events-a";

    @Inject
    RedisDataSource redisDataSource;

    @Inject
    ObjectMapper objectMapper;

    private StreamCommands<String, String, String> streams;

    @BeforeEach
    void setUp() {
        streams = redisDataSource.stream(String.class);
        redisDataSource.key().del(STREAM);
    }

    @Test
    void givenValidCreate_whenPosted_thenEventPublishedToStream() {
        given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "AAPL",
                          "transactionType": "BUY",
                          "assetType": "COMMON_STOCK",
                          "quantity": 10,
                          "price": 100,
                          "currency": "USD",
                          "transactionDate": "2024-01-01",
                          "exchange": "NASDAQ"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(201);

        List<StreamMessage<String, String, String>> entries = streams.xrange(STREAM, StreamRange.of("-", "+"));
        assertEquals(1, entries.size());

        JsonNode envelope = envelopeOf(entries.getFirst());
        assertEquals("transaction.created", envelope.get("eventType").asText());
        assertEquals(1, envelope.get("version").asInt());

        JsonNode payload = envelope.get("payload");
        assertEquals("AAPL", payload.get("ticker").asText());
        assertEquals("NASDAQ", payload.get("exchange").asText());
        assertEquals("USD", payload.get("currency").asText());
        assertEquals(0, new BigDecimal("10").compareTo(payload.get("quantity").decimalValue()));
        assertEquals(0, new BigDecimal("100").compareTo(payload.get("price").decimalValue()));
    }

    @Test
    void givenNullExchange_whenPosted_thenExchangeFieldOmitted() {
        given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "MSFT",
                          "transactionType": "BUY",
                          "assetType": "COMMON_STOCK",
                          "quantity": 5,
                          "price": 200,
                          "currency": "USD",
                          "transactionDate": "2024-01-01"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(201);

        List<StreamMessage<String, String, String>> entries = streams.xrange(STREAM, StreamRange.of("-", "+"));
        assertEquals(1, entries.size());
        assertFalse(envelopeOf(entries.getFirst()).get("payload").has("exchange"));
    }

    /**
     * Entries carry one "payload" stream field holding the JSON envelope — the shape consumers
     * deserialize. Asserting through it keeps this test honest about the published contract.
     */
    private JsonNode envelopeOf(StreamMessage<String, String, String> entry) {
        Map<String, String> fields = entry.payload();
        assertTrue(fields.containsKey("payload"), "entry must carry a 'payload' field");
        try {
            return objectMapper.readTree(fields.get("payload"));
        } catch (Exception exception) {
            throw new AssertionError("payload field is not valid JSON: " + fields.get("payload"), exception);
        }
    }

    @Test
    void givenOversell_whenPosted_thenNoEventPublished() {
        given()
                .header("X-User-Id", USER_A)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "ticker": "TSLA",
                          "transactionType": "SELL",
                          "assetType": "COMMON_STOCK",
                          "quantity": 5,
                          "price": 200,
                          "currency": "USD",
                          "transactionDate": "2024-01-01"
                        }
                        """)
                .when()
                .post("/api/transactions")
                .then()
                .statusCode(409);

        List<StreamMessage<String, String, String>> entries = streams.xrange(STREAM, StreamRange.of("-", "+"));
        assertTrue(entries.isEmpty());
    }
}

package com.portfolio.integration.events;

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

        Map<String, String> fields = entries.getFirst().payload();
        assertEquals("AAPL", fields.get("ticker"));
        assertEquals("NASDAQ", fields.get("exchange"));
        assertEquals("USD", fields.get("currency"));
        assertEquals("10", fields.get("quantity"));
        assertEquals("100", fields.get("price"));
        assertEquals("transaction.created", fields.get("eventType"));
        assertEquals("1", fields.get("version"));
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
        assertFalse(entries.getFirst().payload().containsKey("exchange"));
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

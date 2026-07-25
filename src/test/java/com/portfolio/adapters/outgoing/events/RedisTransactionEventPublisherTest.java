package com.portfolio.adapters.outgoing.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.model.event.TransactionCreatedEvent;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class RedisTransactionEventPublisherTest {

    private static final String STREAM_NAME = "transaction:created";

    @SuppressWarnings("unchecked")
    private final ReactiveStreamCommands<String, String, String> streams = Mockito.mock(ReactiveStreamCommands.class);
    private final ReactiveRedisDataSource redis = Mockito.mock(ReactiveRedisDataSource.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private RedisTransactionEventPublisher publisher;

    @BeforeEach
    void setUp() {
        when(redis.stream(String.class)).thenReturn(streams);
        when(streams.xadd(eq(STREAM_NAME), any(XAddArgs.class), anyMap()))
                .thenReturn(Uni.createFrom().item("0-1"));
        publisher = new RedisTransactionEventPublisher(redis, objectMapper, STREAM_NAME, 10_000L);
    }

    /**
     * Consumers read a single "payload" field and deserialize the envelope out of it. Publishing
     * the event as flat stream fields instead makes every message unparseable on their side.
     */
    @Test
    void givenFullTransaction_whenPublish_thenEntryIsSinglePayloadFieldHoldingEnvelope() throws Exception {
        Transaction transaction = fullTransaction();
        TransactionCreatedEvent event = new TransactionCreatedEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                OffsetDateTime.parse("2024-01-02T03:04:05Z"),
                transaction);

        publisher.publishTransactionCreated(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        Map<String, String> fields = capturedFields();
        assertEquals(Map.of("payload", fields.get("payload")), fields, "entry must carry only 'payload'");

        JsonNode envelope = objectMapper.readTree(fields.get("payload"));
        assertEquals("11111111-1111-1111-1111-111111111111", envelope.get("eventId").asText());
        assertEquals("transaction.created", envelope.get("eventType").asText());
        assertEquals(1, envelope.get("version").asInt());
        assertEquals("2024-01-02T03:04:05Z", envelope.get("occurredAt").asText());
        assertTrue(envelope.hasNonNull("messageCreatedAt"));

        JsonNode payload = envelope.get("payload");
        assertEquals(transaction.id().toString(), payload.get("id").asText());
        assertEquals("user-1", payload.get("userId").asText());
        assertEquals("AAPL", payload.get("ticker").asText());
        assertEquals("BUY", payload.get("transactionType").asText());
        assertEquals("COMMON_STOCK", payload.get("assetType").asText());
        assertEquals(0, new BigDecimal("10").compareTo(payload.get("quantity").decimalValue()));
        assertEquals(0, new BigDecimal("100").compareTo(payload.get("price").decimalValue()));
        assertEquals(0, new BigDecimal("1").compareTo(payload.get("fees").decimalValue()));
        assertEquals("USD", payload.get("currency").asText());
        assertEquals("2024-01-01", payload.get("transactionDate").asText());
        assertFalse(payload.get("isFractional").asBoolean());
        assertEquals(0, BigDecimal.ONE.compareTo(payload.get("fractionalMultiplier").decimalValue()));
        assertEquals("2024-01-01T00:00:00Z", payload.get("createdAt").asText());

        assertEquals("some notes", payload.get("notes").asText());
        assertEquals("USD", payload.get("commissionCurrency").asText());
        assertEquals("NASDAQ", payload.get("exchange").asText());
        assertEquals("US", payload.get("country").asText());
        assertEquals("Apple", payload.get("companyName").asText());
    }

    @Test
    void givenNullableFieldsAbsent_whenPublish_thenOmittedFromPayload() throws Exception {
        TransactionCreatedEvent event = TransactionCreatedEvent.from(transactionWithNullableFieldsMissing());

        publisher.publishTransactionCreated(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        JsonNode payload = objectMapper.readTree(capturedFields().get("payload")).get("payload");

        assertFalse(payload.has("notes"));
        assertFalse(payload.has("commissionCurrency"));
        assertFalse(payload.has("exchange"));
        assertFalse(payload.has("country"));
        assertFalse(payload.has("companyName"));
        assertTrue(payload.has("ticker"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> capturedFields() {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(streams).xadd(eq(STREAM_NAME), any(XAddArgs.class), captor.capture());
        return captor.getValue();
    }

    private static Transaction fullTransaction() {
        return new Transaction(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UserId.of("user-1"),
                "AAPL",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("10"),
                new BigDecimal("100"),
                new BigDecimal("1"),
                Currency.USD,
                LocalDate.of(2024, 1, 1),
                "some notes",
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "Apple",
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));
    }

    private static Transaction transactionWithNullableFieldsMissing() {
        return new Transaction(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UserId.of("user-1"),
                "AAPL",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("10"),
                new BigDecimal("100"),
                new BigDecimal("1"),
                Currency.USD,
                LocalDate.of(2024, 1, 1),
                null,
                false,
                BigDecimal.ONE,
                null,
                null,
                null,
                null,
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));
    }
}

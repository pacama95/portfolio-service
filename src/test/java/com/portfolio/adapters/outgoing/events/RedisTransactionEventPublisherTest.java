package com.portfolio.adapters.outgoing.events;

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
    private RedisTransactionEventPublisher publisher;

    @BeforeEach
    void setUp() {
        when(redis.stream(String.class)).thenReturn(streams);
        when(streams.xadd(eq(STREAM_NAME), any(XAddArgs.class), anyMap()))
                .thenReturn(Uni.createFrom().item("0-1"));
        publisher = new RedisTransactionEventPublisher(redis, STREAM_NAME, 10_000L);
    }

    @Test
    void givenFullTransaction_whenPublish_thenXaddCalledWithAllFields() {
        Transaction transaction = fullTransaction();
        TransactionCreatedEvent event = new TransactionCreatedEvent(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                OffsetDateTime.parse("2024-01-02T03:04:05Z"),
                transaction);

        publisher.publishTransactionCreated(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(streams).xadd(eq(STREAM_NAME), any(XAddArgs.class), captor.capture());
        Map<String, String> fields = captor.getValue();

        assertEquals("11111111-1111-1111-1111-111111111111", fields.get("eventId"));
        assertEquals("transaction.created", fields.get("eventType"));
        assertEquals("1", fields.get("version"));
        assertEquals("2024-01-02T03:04:05Z", fields.get("occurredAt"));

        assertEquals(transaction.id().toString(), fields.get("transactionId"));
        assertEquals("user-1", fields.get("userId"));
        assertEquals("AAPL", fields.get("ticker"));
        assertEquals("BUY", fields.get("transactionType"));
        assertEquals("COMMON_STOCK", fields.get("assetType"));
        assertEquals("10", fields.get("quantity"));
        assertEquals("100", fields.get("price"));
        assertEquals("1", fields.get("fees"));
        assertEquals("USD", fields.get("currency"));
        assertEquals("2024-01-01", fields.get("transactionDate"));
        assertEquals("false", fields.get("fractional"));
        assertEquals("1", fields.get("fractionalMultiplier"));
        assertEquals(transaction.createdAt().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                fields.get("createdAt"));

        assertEquals("some notes", fields.get("notes"));
        assertEquals("USD", fields.get("commissionCurrency"));
        assertEquals("NASDAQ", fields.get("exchange"));
        assertEquals("US", fields.get("country"));
        assertEquals("Apple", fields.get("companyName"));
    }

    @Test
    void givenNullableFieldsAbsent_whenPublish_thenOmittedFromEntry() {
        Transaction transaction = transactionWithNullableFieldsMissing();
        TransactionCreatedEvent event = TransactionCreatedEvent.from(transaction);

        publisher.publishTransactionCreated(event)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted();

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(streams).xadd(eq(STREAM_NAME), any(XAddArgs.class), captor.capture());
        Map<String, String> fields = captor.getValue();

        assertFalse(fields.containsKey("notes"));
        assertFalse(fields.containsKey("commissionCurrency"));
        assertFalse(fields.containsKey("exchange"));
        assertFalse(fields.containsKey("country"));
        assertFalse(fields.containsKey("companyName"));
        assertTrue(fields.containsKey("ticker"));
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

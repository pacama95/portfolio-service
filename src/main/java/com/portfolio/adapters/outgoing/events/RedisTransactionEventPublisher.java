package com.portfolio.adapters.outgoing.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.adapters.outgoing.events.message.EventMessage;
import com.portfolio.adapters.outgoing.events.message.TransactionCreatedData;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.event.TransactionCreatedEvent;
import com.portfolio.core.ports.outgoing.TransactionEventPublisher;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Publishes transaction events as a single {@code payload} stream field holding the JSON
 * {@link EventMessage} envelope. Consumers deserialize that one field, so the entry must not be
 * spread across flat stream fields — doing so makes every message fail their envelope parse and
 * get acked away without ever reaching a handler.
 */
@ApplicationScoped
public class RedisTransactionEventPublisher implements TransactionEventPublisher {

    private static final String PAYLOAD_FIELD = "payload";

    private final ReactiveStreamCommands<String, String, String> streams;
    private final ObjectMapper objectMapper;
    private final String streamName;
    private final long maxLength;

    public RedisTransactionEventPublisher(
            ReactiveRedisDataSource redis,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "application.events.transaction-created.stream", defaultValue = "transaction:created")
                    String streamName,
            @ConfigProperty(name = "application.events.transaction-created.max-length", defaultValue = "10000")
                    long maxLength) {
        this.streams = redis.stream(String.class);
        this.objectMapper = objectMapper;
        this.streamName = streamName;
        this.maxLength = maxLength;
    }

    @Override
    public Uni<Void> publishTransactionCreated(TransactionCreatedEvent event) {
        return serialize(event)
                .flatMap(json -> streams.xadd(
                        streamName,
                        new XAddArgs().maxlen(maxLength).nearlyExactTrimming(),
                        Map.of(PAYLOAD_FIELD, json)))
                .replaceWithVoid();
    }

    private Uni<String> serialize(TransactionCreatedEvent event) {
        return Uni.createFrom().item(() -> {
            try {
                return objectMapper.writeValueAsString(toMessage(event));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Failed to serialize transaction created event", exception);
            }
        });
    }

    private static EventMessage<TransactionCreatedData> toMessage(TransactionCreatedEvent event) {
        return new EventMessage<>(
                event.eventId(),
                toInstant(event.occurredAt()),
                Instant.now(),
                TransactionCreatedEvent.EVENT_TYPE,
                TransactionCreatedEvent.SCHEMA_VERSION,
                toData(event.transaction()));
    }

    private static TransactionCreatedData toData(Transaction transaction) {
        return new TransactionCreatedData(
                transaction.id(),
                transaction.userId().value(),
                transaction.ticker(),
                transaction.transactionType(),
                transaction.assetType(),
                transaction.quantity(),
                transaction.price(),
                transaction.fees(),
                transaction.currency(),
                transaction.transactionDate(),
                transaction.notes(),
                transaction.fractional(),
                transaction.fractionalMultiplier(),
                transaction.commissionCurrency(),
                transaction.exchange(),
                transaction.country(),
                transaction.companyName(),
                toInstant(transaction.createdAt()));
    }

    private static Instant toInstant(OffsetDateTime timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }
}

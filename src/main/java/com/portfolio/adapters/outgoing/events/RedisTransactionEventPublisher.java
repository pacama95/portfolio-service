package com.portfolio.adapters.outgoing.events;

import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.event.TransactionCreatedEvent;
import com.portfolio.core.ports.outgoing.TransactionEventPublisher;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class RedisTransactionEventPublisher implements TransactionEventPublisher {

    private final ReactiveStreamCommands<String, String, String> streams;
    private final String streamName;
    private final long maxLength;

    public RedisTransactionEventPublisher(
            ReactiveRedisDataSource redis,
            @ConfigProperty(name = "application.events.transaction-created.stream", defaultValue = "transaction:created")
                    String streamName,
            @ConfigProperty(name = "application.events.transaction-created.max-length", defaultValue = "10000")
                    long maxLength) {
        this.streams = redis.stream(String.class);
        this.streamName = streamName;
        this.maxLength = maxLength;
    }

    @Override
    public Uni<Void> publishTransactionCreated(TransactionCreatedEvent event) {
        return streams.xadd(streamName, new XAddArgs().maxlen(maxLength).nearlyExactTrimming(), toFields(event))
                .replaceWithVoid();
    }

    private static Map<String, String> toFields(TransactionCreatedEvent event) {
        Transaction transaction = event.transaction();
        Map<String, String> fields = new LinkedHashMap<>();

        fields.put("eventId", event.eventId().toString());
        fields.put("eventType", TransactionCreatedEvent.EVENT_TYPE);
        fields.put("version", String.valueOf(TransactionCreatedEvent.SCHEMA_VERSION));
        fields.put("occurredAt", event.occurredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        fields.put("transactionId", transaction.id().toString());
        fields.put("userId", transaction.userId().value());
        fields.put("ticker", transaction.ticker());
        fields.put("transactionType", transaction.transactionType().name());
        fields.put("assetType", transaction.assetType().name());
        fields.put("quantity", transaction.quantity().toPlainString());
        fields.put("price", transaction.price().toPlainString());
        fields.put("fees", transaction.fees().toPlainString());
        fields.put("currency", transaction.currency().name());
        fields.put("transactionDate", transaction.transactionDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        fields.put("fractional", String.valueOf(transaction.fractional()));
        fields.put("fractionalMultiplier", transaction.fractionalMultiplier().toPlainString());
        fields.put("createdAt", transaction.createdAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        putIfPresent(fields, "notes", transaction.notes());
        putIfPresent(fields, "commissionCurrency", transaction.commissionCurrency() != null
                ? transaction.commissionCurrency().name() : null);
        putIfPresent(fields, "exchange", transaction.exchange());
        putIfPresent(fields, "country", transaction.country());
        putIfPresent(fields, "companyName", transaction.companyName());

        return fields;
    }

    private static void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null) {
            fields.put(key, value);
        }
    }
}

package com.portfolio.core.model.event;

import com.portfolio.core.model.Transaction;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.OffsetDateTime;
import java.util.UUID;

@RegisterForReflection
public record TransactionCreatedEvent(UUID eventId, OffsetDateTime occurredAt, Transaction transaction) {

    public static final String EVENT_TYPE = "transaction.created";
    public static final int SCHEMA_VERSION = 1;

    public static TransactionCreatedEvent from(Transaction transaction) {
        return new TransactionCreatedEvent(UUID.randomUUID(), OffsetDateTime.now(), transaction);
    }
}

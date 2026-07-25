package com.portfolio.adapters.outgoing.events.message;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.UUID;

/**
 * Generic envelope every stream entry is wrapped in. The shape is fixed by the consumers, which
 * read a single {@code payload} stream field and deserialize it into
 * {@code {eventId, occurredAt, messageCreatedAt, eventType, payload}} — see suggestions_api's
 * {@code EventEnvelope}. Consumers ignore unknown properties, so {@code version} rides along
 * without breaking them.
 * <p>
 * Timestamps are pinned to ISO-8601 strings rather than inheriting the global Jackson date
 * setting: this is a wire contract, and a service-wide config change must not silently flip it
 * to epoch numbers that consumers cannot parse.
 *
 * @param <T> payload type
 */
@RegisterForReflection
public record EventMessage<T>(
        UUID eventId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant messageCreatedAt,
        String eventType,
        int version,
        T payload
) {
}

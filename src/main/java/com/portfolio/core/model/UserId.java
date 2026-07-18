package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Objects;

/**
 * Trusted identity of the authenticated caller (propagated via X-User-Id).
 */
@RegisterForReflection
public record UserId(String value) {

    public UserId {
        Objects.requireNonNull(value, "userId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }

    public static UserId of(String value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

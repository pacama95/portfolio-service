package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserContextTest {

    private UserContext userContext;

    @BeforeEach
    void setUp() {
        userContext = new UserContext();
    }

    @Test
    void givenUserIdSet_whenRequireUserId_thenReturnsSameUserId() {
        UserId userId = UserId.of("user-123");
        userContext.setUserId(userId);

        assertEquals(userId, userContext.requireUserId());
    }

    @Test
    void givenNoUserIdSet_whenRequireUserId_thenThrowsIllegalStateException() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, userContext::requireUserId);

        assertEquals("UserId not set on request context", exception.getMessage());
    }
}

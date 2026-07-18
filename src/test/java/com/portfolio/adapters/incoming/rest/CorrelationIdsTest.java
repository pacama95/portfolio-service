package com.portfolio.adapters.incoming.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class CorrelationIdsTest {

    @Test
    void givenNoActiveSpan_whenCurrentTraceId_thenReturnsNull() {
        assertNull(CorrelationIds.currentTraceId());
    }
}

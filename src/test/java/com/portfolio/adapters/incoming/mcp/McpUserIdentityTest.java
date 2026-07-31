package com.portfolio.adapters.incoming.mcp;

import com.portfolio.core.model.UserId;
import io.quarkiverse.mcp.server.ToolCallException;
import io.vertx.core.http.HttpServerRequest;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class McpUserIdentityTest {

    private HttpServerRequest request;
    private McpUserIdentity identity;

    @BeforeEach
    void setUp() {
        MDC.clear();
        request = Mockito.mock(HttpServerRequest.class);
        identity = new McpUserIdentity(request);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void givenValidHeader_whenRequireUserId_thenReturnsTrimmedUserIdAndSetsMdc() {
        when(request.getHeader(McpUserIdentity.USER_ID_HEADER)).thenReturn("  user-42  ");

        UserId userId = identity.requireUserId();

        assertEquals(UserId.of("user-42"), userId);
        assertEquals("user-42", MDC.get("userId"));
    }

    @Test
    void givenMissingHeader_whenRequireUserId_thenThrowsToolCallExceptionWithMissingUserIdCode() {
        when(request.getHeader(McpUserIdentity.USER_ID_HEADER)).thenReturn(null);

        ToolCallException exception = assertThrows(ToolCallException.class, identity::requireUserId);

        assertTrue(exception.getMessage().startsWith("MISSING_USER_ID"));
        assertNull(MDC.get("userId"));
    }

    @Test
    void givenBlankHeader_whenRequireUserId_thenThrowsToolCallExceptionWithMissingUserIdCode() {
        when(request.getHeader(McpUserIdentity.USER_ID_HEADER)).thenReturn("   ");

        ToolCallException exception = assertThrows(ToolCallException.class, identity::requireUserId);

        assertTrue(exception.getMessage().startsWith("MISSING_USER_ID"));
    }
}

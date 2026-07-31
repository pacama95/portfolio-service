package com.portfolio.adapters.incoming.mcp;

import com.portfolio.core.model.UserId;
import io.quarkiverse.mcp.server.ToolCallException;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.MDC;

@ApplicationScoped
public class McpUserIdentity {

    static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ID_MDC_KEY = "userId";

    private final HttpServerRequest request;

    public McpUserIdentity(HttpServerRequest request) {
        this.request = request;
    }

    public UserId requireUserId() {
        String raw = request.getHeader(USER_ID_HEADER);
        if (raw == null || raw.isBlank()) {
            throw new ToolCallException("MISSING_USER_ID: header " + USER_ID_HEADER
                    + " is required; configure it on the MCP client connection.");
        }
        UserId userId = UserId.of(raw.trim());
        MDC.put(USER_ID_MDC_KEY, userId.value());
        return userId;
    }
}

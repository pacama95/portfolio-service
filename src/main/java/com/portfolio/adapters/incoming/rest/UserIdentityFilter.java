package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.model.UserId;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;

import java.util.Map;

@Provider
public class UserIdentityFilter implements ContainerRequestFilter {

    public static final String USER_ID_HEADER = "X-User-Id";

    private final UserContext userContext;

    public UserIdentityFilter(UserContext userContext) {
        this.userContext = userContext;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        if (path.startsWith("q/") || path.equals("q")) {
            return;
        }
        String raw = requestContext.getHeaderString(USER_ID_HEADER);
        if (raw == null || raw.isBlank()) {
            requestContext.abortWith(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error", "MISSING_USER_ID",
                            "message", "Header X-User-Id is required"))
                    .build());
            return;
        }
        UserId userId = UserId.of(raw.trim());
        userContext.setUserId(userId);
        MDC.put(CorrelationIds.USER_ID_MDC_KEY, userId.value());
    }
}

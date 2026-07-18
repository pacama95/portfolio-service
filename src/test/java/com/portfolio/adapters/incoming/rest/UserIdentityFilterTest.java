package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.model.UserId;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserIdentityFilterTest {

    private UserContext userContext;
    private UserIdentityFilter filter;
    private ContainerRequestContext requestContext;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() {
        MDC.clear();
        userContext = new UserContext();
        filter = new UserIdentityFilter(userContext);

        requestContext = Mockito.mock(ContainerRequestContext.class);
        uriInfo = Mockito.mock(UriInfo.class);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void givenQuarkusHealthPath_whenFilter_thenDoesNotSetUserIdOrAbort() {
        when(uriInfo.getPath()).thenReturn("q/health");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(Mockito.any());
        assertThrowsOnRequireUserId();
    }

    @Test
    void givenQuarkusRootPath_whenFilter_thenDoesNotSetUserIdOrAbort() {
        when(uriInfo.getPath()).thenReturn("q");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(Mockito.any());
        assertThrowsOnRequireUserId();
    }

    @Test
    void givenMissingHeader_whenFilter_thenAbortsWith400() {
        when(uriInfo.getPath()).thenReturn("api/transactions");
        when(requestContext.getHeaderString(UserIdentityFilter.USER_ID_HEADER)).thenReturn(null);

        filter.filter(requestContext);

        verify(requestContext).abortWith(Mockito.argThat(response ->
                response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()
                        && response.getEntity() instanceof Map<?, ?> entity
                        && "MISSING_USER_ID".equals(entity.get("error"))));
        assertThrowsOnRequireUserId();
    }

    @Test
    void givenBlankHeader_whenFilter_thenAbortsWith400() {
        when(uriInfo.getPath()).thenReturn("api/transactions");
        when(requestContext.getHeaderString(UserIdentityFilter.USER_ID_HEADER)).thenReturn("   ");

        filter.filter(requestContext);

        verify(requestContext).abortWith(Mockito.argThat(response ->
                response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()));
        assertThrowsOnRequireUserId();
    }

    @Test
    void givenValidHeader_whenFilter_thenSetsTrimmedUserId() {
        when(uriInfo.getPath()).thenReturn("api/transactions");
        when(requestContext.getHeaderString(UserIdentityFilter.USER_ID_HEADER)).thenReturn("  user-42  ");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(Mockito.any());
        assertEquals(UserId.of("user-42"), userContext.requireUserId());
        assertEquals("user-42", MDC.get(CorrelationIds.USER_ID_MDC_KEY));
    }

    @Test
    void givenMissingHeader_whenFilter_thenDoesNotPutUserIdInMdc() {
        when(uriInfo.getPath()).thenReturn("api/transactions");
        when(requestContext.getHeaderString(UserIdentityFilter.USER_ID_HEADER)).thenReturn(null);

        filter.filter(requestContext);

        assertNull(MDC.get(CorrelationIds.USER_ID_MDC_KEY));
    }

    private void assertThrowsOnRequireUserId() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, userContext::requireUserId);
    }
}

package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPriceIngestionRunUseCase;
import com.portfolio.core.ports.incoming.TriggerPriceIngestionUseCase;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceIngestionControllerTest {

    private static final UserId USER_ID = UserId.of("user-1");

    private final TriggerPriceIngestionUseCase triggerPriceIngestionUseCase =
            Mockito.mock(TriggerPriceIngestionUseCase.class);
    private final GetPriceIngestionRunUseCase getPriceIngestionRunUseCase =
            Mockito.mock(GetPriceIngestionRunUseCase.class);
    private final UserContext userContext = Mockito.mock(UserContext.class);

    private PriceIngestionController controller;

    @BeforeEach
    void setUp() {
        controller = new PriceIngestionController(
                triggerPriceIngestionUseCase,
                getPriceIngestionRunUseCase,
                userContext);
        when(userContext.requireUserId()).thenReturn(USER_ID);
    }

    @Test
    void givenAcceptedTrigger_whenTrigger_thenReturns202WithRunId() {
        UUID runId = UUID.randomUUID();
        PriceIngestionController.TriggerPriceIngestionRequest request =
                new PriceIngestionController.TriggerPriceIngestionRequest(
                        LocalDate.of(2024, 1, 1),
                        LocalDate.of(2024, 1, 31),
                        "AAPL",
                        true);
        TriggerPriceIngestionUseCase.Command command = new TriggerPriceIngestionUseCase.Command(
                USER_ID, request.from(), request.to(), request.ticker(), true);

        when(triggerPriceIngestionUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new TriggerPriceIngestionUseCase.Result.Accepted(runId)));

        Response response = controller.trigger(request).await().indefinitely();

        assertEquals(Response.Status.ACCEPTED.getStatusCode(), response.getStatus());
        assertEquals(Map.of("runId", runId), response.getEntity());
    }

    @Test
    void givenConflict_whenTrigger_thenReturns409() {
        PriceIngestionController.TriggerPriceIngestionRequest request =
                new PriceIngestionController.TriggerPriceIngestionRequest(
                        LocalDate.of(2024, 1, 1),
                        LocalDate.of(2024, 1, 31),
                        "AAPL",
                        false);
        TriggerPriceIngestionUseCase.Command command = new TriggerPriceIngestionUseCase.Command(
                USER_ID, request.from(), request.to(), request.ticker(), false);
        TriggerPriceIngestionUseCase.Result.Conflict conflict =
                new TriggerPriceIngestionUseCase.Result.Conflict("run already in progress");

        when(triggerPriceIngestionUseCase.execute(command)).thenReturn(Uni.createFrom().item(conflict));

        Response response = controller.trigger(request).await().indefinitely();

        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        assertEquals(conflict, response.getEntity());
    }

    @Test
    void givenInvalidRequest_whenTrigger_thenReturns400() {
        PriceIngestionController.TriggerPriceIngestionRequest request =
                new PriceIngestionController.TriggerPriceIngestionRequest(
                        LocalDate.of(2024, 2, 1),
                        LocalDate.of(2024, 1, 1),
                        "AAPL",
                        true);
        TriggerPriceIngestionUseCase.Command command = new TriggerPriceIngestionUseCase.Command(
                USER_ID, request.from(), request.to(), request.ticker(), true);
        TriggerPriceIngestionUseCase.Result.InvalidRequest invalid =
                new TriggerPriceIngestionUseCase.Result.InvalidRequest("invalid date range");

        when(triggerPriceIngestionUseCase.execute(command)).thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.trigger(request).await().indefinitely();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(invalid, response.getEntity());
    }

    @Test
    void givenNullIncludeBackfill_whenTrigger_thenDefaultsToFalse() {
        PriceIngestionController.TriggerPriceIngestionRequest request =
                new PriceIngestionController.TriggerPriceIngestionRequest(
                        LocalDate.of(2024, 1, 1),
                        LocalDate.of(2024, 1, 31),
                        "AAPL",
                        null);
        ArgumentCaptor<TriggerPriceIngestionUseCase.Command> commandCaptor =
                ArgumentCaptor.forClass(TriggerPriceIngestionUseCase.Command.class);

        when(triggerPriceIngestionUseCase.execute(Mockito.any()))
                .thenReturn(Uni.createFrom().item(new TriggerPriceIngestionUseCase.Result.InvalidRequest("x")));

        controller.trigger(request).await().indefinitely();

        verify(triggerPriceIngestionUseCase).execute(commandCaptor.capture());
        assertFalse(commandCaptor.getValue().includeBackfill());
    }

    @Test
    void givenLatestRun_whenLatest_thenReturns200() {
        GetPriceIngestionRunUseCase.RunStatus status = sampleRunStatus();

        when(getPriceIngestionRunUseCase.execute(new GetPriceIngestionRunUseCase.Query(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPriceIngestionRunUseCase.Result.Success(status)));

        Response response = controller.latest().await().indefinitely();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(status, response.getEntity());
    }

    @Test
    void givenNoRun_whenLatest_thenReturns404() {
        when(getPriceIngestionRunUseCase.execute(new GetPriceIngestionRunUseCase.Query(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPriceIngestionRunUseCase.Result.NotFound()));

        Response response = controller.latest().await().indefinitely();

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    private static GetPriceIngestionRunUseCase.RunStatus sampleRunStatus() {
        return new GetPriceIngestionRunUseCase.RunStatus(
                UUID.randomUUID(),
                "COMPLETED",
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 31),
                "AAPL",
                1,
                1,
                null,
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T01:00:00Z"));
    }
}

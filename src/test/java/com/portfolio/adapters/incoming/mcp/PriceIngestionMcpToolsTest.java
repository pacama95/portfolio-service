package com.portfolio.adapters.incoming.mcp;

import com.portfolio.adapters.incoming.mcp.dto.IngestionAcceptedDto;
import com.portfolio.adapters.incoming.mcp.dto.IngestionRunDto;
import com.portfolio.adapters.incoming.mcp.mapper.HistoryMcpMapper;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPriceIngestionRunUseCase;
import com.portfolio.core.ports.incoming.TriggerPriceIngestionUseCase;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PriceIngestionMcpToolsTest {

    private static final UserId USER_ID = UserId.of("user-1");
    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 31);

    private final TriggerPriceIngestionUseCase triggerPriceIngestionUseCase = Mockito.mock(TriggerPriceIngestionUseCase.class);
    private final GetPriceIngestionRunUseCase getPriceIngestionRunUseCase = Mockito.mock(GetPriceIngestionRunUseCase.class);
    private final HistoryMcpMapper mapper = Mockito.mock(HistoryMcpMapper.class);
    private final McpUserIdentity identity = Mockito.mock(McpUserIdentity.class);
    private final McpFailureTranslator failureTranslator = Mockito.mock(McpFailureTranslator.class);

    private PriceIngestionMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new PriceIngestionMcpTools(
                triggerPriceIngestionUseCase, getPriceIngestionRunUseCase, mapper, identity, failureTranslator);
        when(identity.requireUserId()).thenReturn(USER_ID);
        when(failureTranslator.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void givenValidArgs_whenTriggerPriceIngestion_thenReturnsRunId() {
        UUID runId = UUID.randomUUID();
        when(triggerPriceIngestionUseCase.execute(
                        new TriggerPriceIngestionUseCase.Command(USER_ID, FROM, TO, "AAPL", true)))
                .thenReturn(Uni.createFrom().item(new TriggerPriceIngestionUseCase.Result.Accepted(runId)));

        IngestionAcceptedDto result = tools.triggerPriceIngestion(FROM, TO, "AAPL", true)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(runId, result.runId());
    }

    @Test
    void givenRunInProgress_whenTriggerPriceIngestion_thenFailsWithConflictCode() {
        when(triggerPriceIngestionUseCase.execute(
                        new TriggerPriceIngestionUseCase.Command(USER_ID, null, null, null, false)))
                .thenReturn(Uni.createFrom().item(new TriggerPriceIngestionUseCase.Result.Conflict("run already in progress")));

        Throwable failure = tools.triggerPriceIngestion(null, null, null, false)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("CONFLICT: run already in progress", failure.getMessage());
    }

    @Test
    void givenInvalidArgs_whenTriggerPriceIngestion_thenFailsWithInvalidRequestCode() {
        when(triggerPriceIngestionUseCase.execute(
                        new TriggerPriceIngestionUseCase.Command(USER_ID, null, null, null, false)))
                .thenReturn(Uni.createFrom().item(new TriggerPriceIngestionUseCase.Result.InvalidRequest("bad range")));

        Throwable failure = tools.triggerPriceIngestion(null, null, null, false)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("INVALID_REQUEST: bad range", failure.getMessage());
    }

    @Test
    void givenExistingRun_whenGetPriceIngestionRun_thenReturnsMappedDto() {
        GetPriceIngestionRunUseCase.RunStatus status = new GetPriceIngestionRunUseCase.RunStatus(
                UUID.randomUUID(), "RUNNING", FROM, TO, "AAPL", 5, 2, null,
                OffsetDateTime.now(), null);
        IngestionRunDto dto = new IngestionRunDto(status.id(), status.status(), status.fromDate(), status.toDate(),
                status.ticker(), status.symbolsRequested(), status.symbolsCompleted(), status.errorMessage(),
                status.startedAt(), status.completedAt());
        when(getPriceIngestionRunUseCase.execute(new GetPriceIngestionRunUseCase.Query(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPriceIngestionRunUseCase.Result.Success(status)));
        when(mapper.toDto(status)).thenReturn(dto);

        IngestionRunDto result = tools.getPriceIngestionRun()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(dto, result);
    }

    @Test
    void givenNoRunTriggered_whenGetPriceIngestionRun_thenFailsWithNotFoundCode() {
        when(getPriceIngestionRunUseCase.execute(new GetPriceIngestionRunUseCase.Query(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPriceIngestionRunUseCase.Result.NotFound()));

        Throwable failure = tools.getPriceIngestionRun()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure.getMessage().startsWith("NOT_FOUND"));
    }
}

package com.portfolio.core.application.usecase.marketdata;

import com.portfolio.core.model.IngestionRun;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPriceIngestionRunUseCase;
import com.portfolio.core.ports.outgoing.PriceIngestionRunRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

class GetPriceIngestionRunServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final PriceIngestionRunRepository runRepository = Mockito.mock(PriceIngestionRunRepository.class);
    private GetPriceIngestionRunService service;

    @BeforeEach
    void setUp() {
        service = new GetPriceIngestionRunService(runRepository);
    }

    @Test
    void givenLatestRun_whenExecute_thenMapsSuccessStatus() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime started = OffsetDateTime.parse("2024-01-01T10:00:00Z");
        OffsetDateTime completed = OffsetDateTime.parse("2024-01-01T10:05:00Z");
        IngestionRun run = new IngestionRun(
                runId,
                USER,
                IngestionRun.Status.COMPLETED,
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 2),
                "AAPL",
                true,
                3,
                3,
                null,
                started,
                completed,
                started,
                completed);
        when(runRepository.findLatest()).thenReturn(Uni.createFrom().item(Optional.of(run)));

        GetPriceIngestionRunUseCase.Result result = service.execute(new GetPriceIngestionRunUseCase.Query(USER))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPriceIngestionRunUseCase.Result.Success success =
                assertInstanceOf(GetPriceIngestionRunUseCase.Result.Success.class, result);
        GetPriceIngestionRunUseCase.RunStatus status = success.status();
        assertEquals(runId, status.id());
        assertEquals("COMPLETED", status.status());
        assertEquals(LocalDate.of(2024, 1, 1), status.fromDate());
        assertEquals(LocalDate.of(2024, 1, 2), status.toDate());
        assertEquals("AAPL", status.ticker());
        assertEquals(3, status.symbolsRequested());
        assertEquals(3, status.symbolsCompleted());
        assertEquals(started, status.startedAt());
        assertEquals(completed, status.completedAt());
    }

    @Test
    void givenNoRun_whenExecute_thenNotFound() {
        when(runRepository.findLatest()).thenReturn(Uni.createFrom().item(Optional.empty()));

        GetPriceIngestionRunUseCase.Result result = service.execute(new GetPriceIngestionRunUseCase.Query(USER))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetPriceIngestionRunUseCase.Result.NotFound.class, result);
    }
}

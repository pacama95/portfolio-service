package com.portfolio.core.application.usecase.marketdata;

import com.portfolio.core.model.IngestionRun;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.TriggerPriceIngestionUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.PriceIngestionRunRepository;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TriggerPriceIngestionServiceTest {

    private static final UserId USER = UserId.of("user-1");
    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 2);
    private static final Duration STALE_RUN_TIMEOUT = Duration.ofHours(3);

    private final TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private final PriceIngestionRunRepository runRepository = Mockito.mock(PriceIngestionRunRepository.class);
    private TriggerPriceIngestionService service;

    @BeforeEach
    void setUp() {
        service = new TriggerPriceIngestionService(
                transactionRepository, marketDataPort, runRepository, STALE_RUN_TIMEOUT);
    }

    @Test
    void givenNullFrom_whenExecute_thenInvalidRequest() {
        TriggerPriceIngestionUseCase.Result result = service.execute(
                new TriggerPriceIngestionUseCase.Command(USER, null, TO, null, false))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(TriggerPriceIngestionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenSameFromAndTo_whenExecute_thenAccepted() {
        LocalDate day = LocalDate.of(2024, 1, 5);
        when(runRepository.hasActiveRun(any(Duration.class))).thenReturn(Uni.createFrom().item(false));
        when(runRepository.save(any(IngestionRun.class))).thenAnswer(invocation ->
                Uni.createFrom().item(invocation.getArgument(0, IngestionRun.class)));

        TriggerPriceIngestionUseCase.Result result = service.execute(
                new TriggerPriceIngestionUseCase.Command(USER, day, day, null, false))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(TriggerPriceIngestionUseCase.Result.Accepted.class, result);
    }

    @Test
    void givenBlankTicker_whenRunIngestion_thenUsesDistinctTickers() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        IngestionRun initial = new IngestionRun(
                runId, USER, IngestionRun.Status.PENDING, FROM, TO, null, false,
                0, 0, null, null, null, now, now);
        TriggerPriceIngestionUseCase.Command command =
                new TriggerPriceIngestionUseCase.Command(USER, FROM, TO, "   ", false);

        when(transactionRepository.findDistinctTickers()).thenReturn(Uni.createFrom().item(List.of("AAPL")));
        when(marketDataPort.getPriceHistory("AAPL", FROM, TO)).thenReturn(Uni.createFrom().item(List.of()));
        when(runRepository.save(any(IngestionRun.class))).thenAnswer(invocation ->
                Uni.createFrom().item(invocation.getArgument(0, IngestionRun.class)));

        service.runIngestion(initial, command)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        verify(transactionRepository).findDistinctTickers();
        verify(marketDataPort).getPriceHistory("AAPL", FROM, TO);
    }

    @Test
    void givenNullTo_whenExecute_thenInvalidRequest() {
        TriggerPriceIngestionUseCase.Result result = service.execute(
                new TriggerPriceIngestionUseCase.Command(USER, FROM, null, null, false))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(TriggerPriceIngestionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenFromAfterTo_whenExecute_thenInvalidRequest() {
        TriggerPriceIngestionUseCase.Result result = service.execute(
                new TriggerPriceIngestionUseCase.Command(
                        USER, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 1, 1), null, false))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(TriggerPriceIngestionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenActiveRun_whenExecute_thenConflict() {
        when(runRepository.hasActiveRun(any(Duration.class))).thenReturn(Uni.createFrom().item(true));

        TriggerPriceIngestionUseCase.Result result = service.execute(
                new TriggerPriceIngestionUseCase.Command(USER, FROM, TO, null, false))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(TriggerPriceIngestionUseCase.Result.Conflict.class, result);
    }

    @Test
    void givenNoActiveRun_whenExecute_thenAcceptedAndSavesPendingRun() {
        when(runRepository.hasActiveRun(any(Duration.class))).thenReturn(Uni.createFrom().item(false));
        when(runRepository.save(any(IngestionRun.class))).thenAnswer(invocation ->
                Uni.createFrom().item(invocation.getArgument(0, IngestionRun.class)));

        TriggerPriceIngestionUseCase.Result result = service.execute(
                new TriggerPriceIngestionUseCase.Command(USER, FROM, TO, "aapl", false))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        TriggerPriceIngestionUseCase.Result.Accepted accepted =
                assertInstanceOf(TriggerPriceIngestionUseCase.Result.Accepted.class, result);
        assertNotNull(accepted.runId());

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        IngestionRun pending = captor.getAllValues().getFirst();
        assertEquals(IngestionRun.Status.PENDING, pending.status());
        assertEquals(USER, pending.userId());
        assertEquals(FROM, pending.fromDate());
        assertEquals(TO, pending.toDate());
        assertEquals("aapl", pending.ticker());
    }

    @Test
    void givenValidRun_whenRunIngestion_thenCompletesSuccessfully() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        IngestionRun initial = new IngestionRun(
                runId, USER, IngestionRun.Status.PENDING, FROM, TO, "AAPL", false,
                0, 0, null, null, null, now, now);
        TriggerPriceIngestionUseCase.Command command =
                new TriggerPriceIngestionUseCase.Command(USER, FROM, TO, "AAPL", false);

        when(marketDataPort.getPriceHistory("AAPL", FROM, TO)).thenReturn(Uni.createFrom().item(List.of()));
        when(runRepository.save(any(IngestionRun.class))).thenAnswer(invocation ->
                Uni.createFrom().item(invocation.getArgument(0, IngestionRun.class)));

        service.runIngestion(initial, command)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        IngestionRun completed = captor.getAllValues().getLast();
        assertEquals(IngestionRun.Status.COMPLETED, completed.status());
        assertEquals(1, completed.symbolsRequested());
        assertEquals(1, completed.symbolsCompleted());
        assertNotNull(completed.completedAt());
    }

    @Test
    void givenProviderFailure_whenRunIngestion_thenMarksRunFailed() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        IngestionRun initial = new IngestionRun(
                runId, USER, IngestionRun.Status.PENDING, FROM, TO, null, false,
                0, 0, null, null, null, now, now);
        TriggerPriceIngestionUseCase.Command command =
                new TriggerPriceIngestionUseCase.Command(USER, FROM, TO, null, false);

        when(transactionRepository.findDistinctTickers()).thenReturn(Uni.createFrom().item(List.of("AAPL")));
        when(runRepository.save(any(IngestionRun.class))).thenAnswer(invocation ->
                Uni.createFrom().item(invocation.getArgument(0, IngestionRun.class)));
        when(marketDataPort.getPriceHistory("AAPL", FROM, TO))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("provider down")));

        service.runIngestion(initial, command)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(RuntimeException.class, "provider down");

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        IngestionRun failed = captor.getAllValues().stream()
                .filter(run -> run.status() == IngestionRun.Status.FAILED)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertEquals(IngestionRun.Status.FAILED, failed.status());
        assertEquals("provider down", failed.errorMessage());
        assertNotNull(failed.completedAt());
    }
}

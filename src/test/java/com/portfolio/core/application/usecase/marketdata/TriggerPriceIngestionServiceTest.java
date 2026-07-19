package com.portfolio.core.application.usecase.marketdata;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.IngestionRun;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TriggerPriceIngestionServiceTest {

    private static final UserId USER = UserId.of("user-1");
    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 2);
    private static final Duration STALE_RUN_TIMEOUT = Duration.ofHours(3);
    private static final Duration SYMBOL_FETCH_DELAY = Duration.ofMillis(1);

    private final TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private final PriceIngestionRunRepository runRepository = Mockito.mock(PriceIngestionRunRepository.class);
    private TriggerPriceIngestionService service;

    @BeforeEach
    void setUp() {
        service = new TriggerPriceIngestionService(
                transactionRepository, marketDataPort, runRepository, STALE_RUN_TIMEOUT, SYMBOL_FETCH_DELAY);
    }

    private static Transaction buyTransaction(String ticker, UserId user) {
        return new Transaction(
                UUID.randomUUID(),
                user,
                ticker,
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("10"),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                Currency.USD,
                LocalDate.of(2024, 1, 1),
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                ticker + " Inc",
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));
    }

    private void stubStartRunAccepted() {
        when(runRepository.startRunIfNoneActive(any(Duration.class), any(IngestionRun.class)))
                .thenAnswer(invocation -> Uni.createFrom().item(
                        Optional.of(invocation.getArgument(1, IngestionRun.class))));
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
        stubStartRunAccepted();
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
    void givenBlankTicker_whenRunIngestion_thenUsesTickersWithOpenPositions() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        IngestionRun initial = new IngestionRun(
                runId, USER, IngestionRun.Status.PENDING, FROM, TO, null, false,
                0, 0, null, null, null, now, now);
        TriggerPriceIngestionUseCase.Command command =
                new TriggerPriceIngestionUseCase.Command(USER, FROM, TO, "   ", false);

        when(transactionRepository.findAll())
                .thenReturn(Uni.createFrom().item(List.of(buyTransaction("AAPL", USER))));
        when(marketDataPort.getPriceHistory("AAPL", FROM, TO)).thenReturn(Uni.createFrom().item(List.of()));
        when(runRepository.save(any(IngestionRun.class))).thenAnswer(invocation ->
                Uni.createFrom().item(invocation.getArgument(0, IngestionRun.class)));

        service.runIngestion(initial, command)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        verify(transactionRepository).findAll();
        verify(marketDataPort).getPriceHistory("AAPL", FROM, TO);
    }

    @Test
    void givenClosedPosition_whenRunIngestion_thenExcludesFullySoldTicker() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        IngestionRun initial = new IngestionRun(
                runId, USER, IngestionRun.Status.PENDING, FROM, TO, null, false,
                0, 0, null, null, null, now, now);
        TriggerPriceIngestionUseCase.Command command =
                new TriggerPriceIngestionUseCase.Command(USER, FROM, TO, null, false);

        Transaction buy = buyTransaction("AAPL", USER);
        Transaction fullSell = new Transaction(
                UUID.randomUUID(), USER, "AAPL", TransactionType.SELL, AssetType.COMMON_STOCK,
                new BigDecimal("10"), new BigDecimal("120"), BigDecimal.ZERO, Currency.USD,
                LocalDate.of(2024, 1, 5), null, false, BigDecimal.ONE, Currency.USD,
                "NASDAQ", "US", "AAPL Inc",
                OffsetDateTime.parse("2024-01-05T00:00:00Z"), OffsetDateTime.parse("2024-01-05T00:00:00Z"));

        when(transactionRepository.findAll())
                .thenReturn(Uni.createFrom().item(List.of(buy, fullSell)));
        when(runRepository.save(any(IngestionRun.class))).thenAnswer(invocation ->
                Uni.createFrom().item(invocation.getArgument(0, IngestionRun.class)));

        service.runIngestion(initial, command)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        verify(marketDataPort, Mockito.never()).getPriceHistory(any(), any(), any());
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
        when(runRepository.startRunIfNoneActive(any(Duration.class), any(IngestionRun.class)))
                .thenReturn(Uni.createFrom().item(Optional.empty()));

        TriggerPriceIngestionUseCase.Result result = service.execute(
                new TriggerPriceIngestionUseCase.Command(USER, FROM, TO, null, false))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(TriggerPriceIngestionUseCase.Result.Conflict.class, result);
    }

    @Test
    void givenNoActiveRun_whenExecute_thenAcceptedAndSavesPendingRun() {
        stubStartRunAccepted();
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
        verify(runRepository).startRunIfNoneActive(any(Duration.class), captor.capture());
        IngestionRun pending = captor.getValue();
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
    void givenSingleSymbolFailure_whenRunIngestion_thenCompletesPartially() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        IngestionRun initial = new IngestionRun(
                runId, USER, IngestionRun.Status.PENDING, FROM, TO, null, false,
                0, 0, null, null, null, now, now);
        TriggerPriceIngestionUseCase.Command command =
                new TriggerPriceIngestionUseCase.Command(USER, FROM, TO, null, false);

        when(transactionRepository.findAll())
                .thenReturn(Uni.createFrom().item(List.of(buyTransaction("AAPL", USER))));
        when(runRepository.save(any(IngestionRun.class))).thenAnswer(invocation ->
                Uni.createFrom().item(invocation.getArgument(0, IngestionRun.class)));
        when(marketDataPort.getPriceHistory("AAPL", FROM, TO))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("provider down")));

        service.runIngestion(initial, command)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        IngestionRun completed = captor.getAllValues().getLast();
        assertEquals(IngestionRun.Status.PARTIAL, completed.status());
        assertEquals(0, completed.symbolsCompleted());
        assertNotNull(completed.errorMessage());
        assertTrue(completed.errorMessage().contains("AAPL"));
        assertNotNull(completed.completedAt());
    }

    @Test
    void givenOneOfTwoSymbolsFails_whenRunIngestion_thenPartialWithSuccessCounted() {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        IngestionRun initial = new IngestionRun(
                runId, USER, IngestionRun.Status.PENDING, FROM, TO, null, false,
                0, 0, null, null, null, now, now);
        TriggerPriceIngestionUseCase.Command command =
                new TriggerPriceIngestionUseCase.Command(USER, FROM, TO, null, false);

        when(transactionRepository.findAll()).thenReturn(Uni.createFrom().item(
                List.of(buyTransaction("AAPL", USER), buyTransaction("MSFT", USER))));
        when(runRepository.save(any(IngestionRun.class))).thenAnswer(invocation ->
                Uni.createFrom().item(invocation.getArgument(0, IngestionRun.class)));
        when(marketDataPort.getPriceHistory("AAPL", FROM, TO)).thenReturn(Uni.createFrom().item(List.of()));
        when(marketDataPort.getPriceHistory("MSFT", FROM, TO))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("rate limited")));

        service.runIngestion(initial, command)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        ArgumentCaptor<IngestionRun> captor = ArgumentCaptor.forClass(IngestionRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        IngestionRun completed = captor.getAllValues().getLast();
        assertEquals(IngestionRun.Status.PARTIAL, completed.status());
        assertEquals(2, completed.symbolsRequested());
        assertEquals(1, completed.symbolsCompleted());
        assertNotNull(completed.errorMessage());
        assertTrue(completed.errorMessage().contains("MSFT"));
    }
}

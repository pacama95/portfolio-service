package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.DeleteTransactionUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class DeleteTransactionServiceTest {

    private static final UserId USER = UserId.of("u1");

    private final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
    private DeleteTransactionService service;

    @BeforeEach
    void setUp() {
        service = new DeleteTransactionService(repository);
    }

    @Test
    void givenExistingId_whenDelete_thenSuccess() {
        UUID id = UUID.randomUUID();
        Transaction target = tx(id, "AAPL", TransactionType.BUY, "5", LocalDate.of(2024, 1, 1));
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.of(target)));
        stubLock(Map.of("AAPL", List.of(target)));
        when(repository.deleteById(USER, id)).thenReturn(Uni.createFrom().item(true));

        DeleteTransactionUseCase.Result result = service.execute(
                new DeleteTransactionUseCase.Command(USER, id))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(DeleteTransactionUseCase.Result.Success.class, result);
    }

    @Test
    void givenMissingId_whenDelete_thenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.empty()));

        DeleteTransactionUseCase.Result result = service.execute(
                new DeleteTransactionUseCase.Command(USER, id))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(DeleteTransactionUseCase.Result.NotFound.class, result);
    }

    @Test
    void givenDeleteWouldOversellRemainingLedger_whenDelete_thenConflict() {
        UUID buyId = UUID.randomUUID();
        Transaction buy = tx(buyId, "AAPL", TransactionType.BUY, "5", LocalDate.of(2024, 1, 1));
        Transaction sell = tx(UUID.randomUUID(), "AAPL", TransactionType.SELL, "5", LocalDate.of(2024, 1, 2));
        when(repository.findById(USER, buyId)).thenReturn(Uni.createFrom().item(Optional.of(buy)));
        stubLock(Map.of("AAPL", List.of(buy, sell)));

        DeleteTransactionUseCase.Result result = service.execute(
                new DeleteTransactionUseCase.Command(USER, buyId))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(DeleteTransactionUseCase.Result.Conflict.class, result);
    }

    @SuppressWarnings("unchecked")
    private void stubLock(Map<String, List<Transaction>> byTicker) {
        when(repository.withTickersLocked(any(), any(), any())).thenAnswer(invocation -> {
            Function<Map<String, List<Transaction>>, Uni<Object>> action = invocation.getArgument(2);
            return action.apply(byTicker);
        });
    }

    private static Transaction tx(UUID id, String ticker, TransactionType type, String qty, LocalDate date) {
        return new Transaction(
                id,
                USER,
                ticker,
                type,
                AssetType.COMMON_STOCK,
                new BigDecimal(qty),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                Currency.USD,
                date,
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
}

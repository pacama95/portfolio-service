package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetTransactionUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

class GetTransactionServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
    private GetTransactionService service;

    @BeforeEach
    void setUp() {
        service = new GetTransactionService(repository);
    }

    @Test
    void givenExistingTransaction_whenExecute_thenSuccess() {
        UUID id = UUID.randomUUID();
        Transaction transaction = transaction(id, "AAPL");
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.of(transaction)));

        GetTransactionUseCase.Result result = service.execute(new GetTransactionUseCase.Query(USER, id))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetTransactionUseCase.Result.Success success =
                assertInstanceOf(GetTransactionUseCase.Result.Success.class, result);
        assertEquals(id, success.transaction().id());
        assertEquals("AAPL", success.transaction().ticker());
    }

    @Test
    void givenMissingTransaction_whenExecute_thenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.empty()));

        GetTransactionUseCase.Result result = service.execute(new GetTransactionUseCase.Query(USER, id))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetTransactionUseCase.Result.NotFound notFound =
                assertInstanceOf(GetTransactionUseCase.Result.NotFound.class, result);
        assertEquals(id, notFound.id());
    }

    private static Transaction transaction(UUID id, String ticker) {
        return new Transaction(
                id,
                USER,
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
}

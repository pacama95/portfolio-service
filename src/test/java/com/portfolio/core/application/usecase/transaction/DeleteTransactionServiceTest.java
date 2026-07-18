package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.DeleteTransactionUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

class DeleteTransactionServiceTest {

    private final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
    private DeleteTransactionService service;

    @BeforeEach
    void setUp() {
        service = new DeleteTransactionService(repository);
    }

    @Test
    void givenExistingId_whenDelete_thenSuccess() {
        UUID id = UUID.randomUUID();
        when(repository.deleteById(UserId.of("u1"), id)).thenReturn(Uni.createFrom().item(true));

        DeleteTransactionUseCase.Result result = service.execute(
                new DeleteTransactionUseCase.Command(UserId.of("u1"), id))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(DeleteTransactionUseCase.Result.Success.class, result);
    }

    @Test
    void givenMissingId_whenDelete_thenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.deleteById(UserId.of("u1"), id)).thenReturn(Uni.createFrom().item(false));

        DeleteTransactionUseCase.Result result = service.execute(
                new DeleteTransactionUseCase.Command(UserId.of("u1"), id))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(DeleteTransactionUseCase.Result.NotFound.class, result);
    }
}

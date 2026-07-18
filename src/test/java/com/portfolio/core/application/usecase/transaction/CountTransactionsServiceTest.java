package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.CountTransactionsUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CountTransactionsServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
    private CountTransactionsService service;

    @BeforeEach
    void setUp() {
        service = new CountTransactionsService(repository);
    }

    @Test
    void givenNullTicker_whenExecute_thenCountsAll() {
        when(repository.count(USER)).thenReturn(Uni.createFrom().item(42L));

        CountTransactionsUseCase.Result result = service.execute(CountTransactionsUseCase.Query.all(USER))
                .await().indefinitely();

        CountTransactionsUseCase.Result.Success success =
                assertInstanceOf(CountTransactionsUseCase.Result.Success.class, result);
        assertEquals(42L, success.count());
        verify(repository).count(USER);
    }

    @Test
    void givenTicker_whenExecute_thenCountsByTicker() {
        when(repository.countByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(3L));

        CountTransactionsUseCase.Result result = service.execute(
                CountTransactionsUseCase.Query.byTicker(USER, "AAPL")).await().indefinitely();

        CountTransactionsUseCase.Result.Success success =
                assertInstanceOf(CountTransactionsUseCase.Result.Success.class, result);
        assertEquals(3L, success.count());
        verify(repository).countByTicker(USER, "AAPL");
    }

    @Test
    void givenBlankTicker_whenExecute_thenCountsAll() {
        when(repository.count(USER)).thenReturn(Uni.createFrom().item(7L));

        CountTransactionsUseCase.Result result = service.execute(new CountTransactionsUseCase.Query(USER, "  "))
                .await().indefinitely();

        CountTransactionsUseCase.Result.Success success =
                assertInstanceOf(CountTransactionsUseCase.Result.Success.class, result);
        assertEquals(7L, success.count());
        verify(repository).count(USER);
    }
}

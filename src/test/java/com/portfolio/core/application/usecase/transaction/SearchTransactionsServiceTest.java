package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionSortOrder;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.SearchTransactionsUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchTransactionsServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
    private SearchTransactionsService service;

    @BeforeEach
    void setUp() {
        service = new SearchTransactionsService(repository);
    }

    @Test
    void givenNullSortOrder_whenExecute_thenDefaultsToDesc() {
        Transaction tx = transaction("AAPL");
        when(repository.search(eq(USER), isNull(), isNull(), isNull(), isNull(), eq(TransactionSortOrder.DESC)))
                .thenReturn(Uni.createFrom().item(List.of(tx)));

        SearchTransactionsUseCase.Result result = service.execute(
                new SearchTransactionsUseCase.Query(USER, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        SearchTransactionsUseCase.Result.Success success =
                assertInstanceOf(SearchTransactionsUseCase.Result.Success.class, result);
        assertEquals(1, success.transactions().size());

        ArgumentCaptor<TransactionSortOrder> sortCaptor = ArgumentCaptor.forClass(TransactionSortOrder.class);
        verify(repository).search(eq(USER), isNull(), isNull(), isNull(), isNull(), sortCaptor.capture());
        assertEquals(TransactionSortOrder.DESC, sortCaptor.getValue());
    }

    @Test
    void givenTickerQuery_whenExecute_thenReturnsMatchingTransactions() {
        Transaction tx = transaction("AAPL");
        when(repository.search(
                USER, "AAPL", null, null, null, TransactionSortOrder.DESC))
                .thenReturn(Uni.createFrom().item(List.of(tx)));

        SearchTransactionsUseCase.Result result = service.execute(
                SearchTransactionsUseCase.Query.byTicker(USER, "AAPL"))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        SearchTransactionsUseCase.Result.Success success =
                assertInstanceOf(SearchTransactionsUseCase.Result.Success.class, result);
        assertEquals(1, success.transactions().size());
        assertEquals("AAPL", success.transactions().getFirst().ticker());
    }

    private static Transaction transaction(String ticker) {
        return new Transaction(
                UUID.randomUUID(),
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

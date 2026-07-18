package com.portfolio.core.application.usecase.history;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyPositionSnapshot;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetDailyPositionHistoryUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetDailyPositionHistoryServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
    private GetDailyPositionHistoryService service;

    @BeforeEach
    void setUp() {
        service = new GetDailyPositionHistoryService(transactionRepository);
    }

    @Test
    void givenNullTo_whenExecute_thenInvalidRequest() {
        GetDailyPositionHistoryUseCase.Result result = service.execute(
                new GetDailyPositionHistoryUseCase.Query(USER, LocalDate.of(2024, 1, 1), null, null))
                .await().indefinitely();

        assertInstanceOf(GetDailyPositionHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenSameFromAndTo_whenExecute_thenReturnsSnapshotsForSingleDay() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));

        GetDailyPositionHistoryUseCase.Result result = service.execute(
                new GetDailyPositionHistoryUseCase.Query(
                        USER, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1), null))
                .await().indefinitely();

        GetDailyPositionHistoryUseCase.Result.Success success =
                assertInstanceOf(GetDailyPositionHistoryUseCase.Result.Success.class, result);
        assertEquals(1, success.snapshots().size());
    }

    @Test
    void givenNullFrom_whenExecute_thenInvalidRequest() {
        GetDailyPositionHistoryUseCase.Result result = service.execute(
                new GetDailyPositionHistoryUseCase.Query(USER, null, LocalDate.of(2024, 1, 31), null))
                .await().indefinitely();

        assertInstanceOf(GetDailyPositionHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenFromAfterTo_whenExecute_thenInvalidRequest() {
        GetDailyPositionHistoryUseCase.Result result = service.execute(
                new GetDailyPositionHistoryUseCase.Query(
                        USER, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 1, 1), null))
                .await().indefinitely();

        assertInstanceOf(GetDailyPositionHistoryUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenValidQueryWithoutTicker_whenExecute_thenReturnsSnapshotsFromAllTransactions() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)),
                tx("MSFT", TransactionType.BUY, "5", "200", LocalDate.of(2024, 1, 2)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));

        GetDailyPositionHistoryUseCase.Result result = service.execute(
                new GetDailyPositionHistoryUseCase.Query(
                        USER, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), null))
                .await().indefinitely();

        GetDailyPositionHistoryUseCase.Result.Success success =
                assertInstanceOf(GetDailyPositionHistoryUseCase.Result.Success.class, result);
        assertEquals(2, success.snapshots().size());
        verify(transactionRepository).findAll(USER);
    }

    @Test
    void givenValidQueryWithTicker_whenExecute_thenFiltersByTicker() {
        List<Transaction> txs = List.of(tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(txs));

        GetDailyPositionHistoryUseCase.Result result = service.execute(
                new GetDailyPositionHistoryUseCase.Query(
                        USER, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), "  aapl "))
                .await().indefinitely();

        GetDailyPositionHistoryUseCase.Result.Success success =
                assertInstanceOf(GetDailyPositionHistoryUseCase.Result.Success.class, result);
        assertEquals(1, success.snapshots().size());
        DailyPositionSnapshot snapshot = success.snapshots().getFirst();
        assertEquals("AAPL", snapshot.ticker());
        verify(transactionRepository).findByTicker(eq(USER), eq("AAPL"));
    }

    private static Transaction tx(String ticker, TransactionType type, String qty, String price, LocalDate date) {
        return new Transaction(
                UUID.randomUUID(),
                USER,
                ticker,
                type,
                AssetType.COMMON_STOCK,
                new BigDecimal(qty),
                new BigDecimal(price),
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

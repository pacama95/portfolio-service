package com.portfolio.core.application.usecase.history;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.CapitalFlowKind;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetCapitalFlowsUseCase;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

class GetCapitalFlowsServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
    private GetCapitalFlowsService service;

    @BeforeEach
    void setUp() {
        service = new GetCapitalFlowsService(transactionRepository, 5500);
    }

    @Test
    void givenRangeExceedsMax_whenExecute_thenInvalidRequest() {
        GetCapitalFlowsService strict = new GetCapitalFlowsService(transactionRepository, 5);

        GetCapitalFlowsUseCase.Result result = strict.execute(
                new GetCapitalFlowsUseCase.Query(USER, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 20)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetCapitalFlowsUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNullFrom_whenExecute_thenInvalidRequest() {
        GetCapitalFlowsUseCase.Result result = service.execute(
                new GetCapitalFlowsUseCase.Query(USER, null, LocalDate.of(2024, 1, 31)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetCapitalFlowsUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenSameFromAndTo_whenExecute_thenReturnsFlowsForSingleDay() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", "2", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));

        GetCapitalFlowsUseCase.Result result = service.execute(
                new GetCapitalFlowsUseCase.Query(
                        USER, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetCapitalFlowsUseCase.Result.Success success =
                assertInstanceOf(GetCapitalFlowsUseCase.Result.Success.class, result);
        assertEquals(1, success.flows().size());
    }

    @Test
    void givenNullTo_whenExecute_thenInvalidRequest() {
        GetCapitalFlowsUseCase.Result result = service.execute(
                new GetCapitalFlowsUseCase.Query(USER, LocalDate.of(2024, 1, 1), null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetCapitalFlowsUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenFromAfterTo_whenExecute_thenInvalidRequest() {
        GetCapitalFlowsUseCase.Result result = service.execute(
                new GetCapitalFlowsUseCase.Query(
                        USER, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 1, 1)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(GetCapitalFlowsUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenValidQuery_whenExecute_thenReturnsCapitalFlows() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", "2", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "5", "120", "1", LocalDate.of(2024, 1, 2)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));

        GetCapitalFlowsUseCase.Result result = service.execute(
                new GetCapitalFlowsUseCase.Query(
                        USER, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31)))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetCapitalFlowsUseCase.Result.Success success =
                assertInstanceOf(GetCapitalFlowsUseCase.Result.Success.class, result);
        assertEquals(2, success.flows().size());
        assertEquals(CapitalFlowKind.BUY, success.flows().get(0).kind());
        assertEquals(CapitalFlowKind.SELL, success.flows().get(1).kind());
    }

    private static Transaction tx(
            String ticker, TransactionType type, String qty, String price, String fees, LocalDate date) {
        return new Transaction(
                UUID.randomUUID(),
                USER,
                ticker,
                type,
                AssetType.COMMON_STOCK,
                new BigDecimal(qty),
                new BigDecimal(price),
                new BigDecimal(fees),
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

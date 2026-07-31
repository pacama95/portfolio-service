package com.portfolio.adapters.incoming.mcp;

import com.portfolio.adapters.incoming.mcp.dto.DeleteConfirmationDto;
import com.portfolio.adapters.incoming.mcp.dto.TransactionCountDto;
import com.portfolio.adapters.incoming.mcp.dto.TransactionDto;
import com.portfolio.adapters.incoming.mcp.dto.TransactionListDto;
import com.portfolio.adapters.incoming.mcp.mapper.TransactionMcpMapper;
import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionSortOrder;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.CountTransactionsUseCase;
import com.portfolio.core.ports.incoming.CreateTransactionUseCase;
import com.portfolio.core.ports.incoming.DeleteTransactionUseCase;
import com.portfolio.core.ports.incoming.GetTransactionUseCase;
import com.portfolio.core.ports.incoming.SearchTransactionsUseCase;
import com.portfolio.core.ports.incoming.UpdateTransactionUseCase;
import io.quarkiverse.mcp.server.ToolCallException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class TransactionMcpToolsTest {

    private static final UserId USER_ID = UserId.of("user-1");

    private final CreateTransactionUseCase createTransactionUseCase = Mockito.mock(CreateTransactionUseCase.class);
    private final UpdateTransactionUseCase updateTransactionUseCase = Mockito.mock(UpdateTransactionUseCase.class);
    private final DeleteTransactionUseCase deleteTransactionUseCase = Mockito.mock(DeleteTransactionUseCase.class);
    private final GetTransactionUseCase getTransactionUseCase = Mockito.mock(GetTransactionUseCase.class);
    private final SearchTransactionsUseCase searchTransactionsUseCase = Mockito.mock(SearchTransactionsUseCase.class);
    private final CountTransactionsUseCase countTransactionsUseCase = Mockito.mock(CountTransactionsUseCase.class);
    private final TransactionMcpMapper mapper = Mockito.mock(TransactionMcpMapper.class);
    private final McpUserIdentity identity = Mockito.mock(McpUserIdentity.class);
    private final McpFailureTranslator failureTranslator = Mockito.mock(McpFailureTranslator.class);

    private TransactionMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new TransactionMcpTools(
                createTransactionUseCase,
                updateTransactionUseCase,
                deleteTransactionUseCase,
                getTransactionUseCase,
                searchTransactionsUseCase,
                countTransactionsUseCase,
                mapper,
                identity,
                failureTranslator);
        when(identity.requireUserId()).thenReturn(USER_ID);
        when(failureTranslator.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Transaction sampleTransaction() {
        return new Transaction(
                UUID.randomUUID(), USER_ID, "AAPL", TransactionType.BUY, AssetType.COMMON_STOCK,
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("1"), Currency.USD,
                LocalDate.of(2024, 1, 1), null, false, BigDecimal.ONE, Currency.USD,
                "NASDAQ", "US", "Apple", OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static TransactionDto sampleDto(Transaction transaction) {
        return new TransactionDto(
                transaction.id(), transaction.ticker(), transaction.transactionType(), transaction.assetType(),
                transaction.quantity(), transaction.price(), transaction.fees(), transaction.currency(),
                transaction.transactionDate(), transaction.notes(), transaction.fractional(),
                transaction.fractionalMultiplier(), transaction.commissionCurrency(), transaction.exchange(),
                transaction.country(), transaction.companyName(), transaction.totalValue(), transaction.totalCost(),
                transaction.createdAt(), transaction.updatedAt());
    }

    private static CreateTransactionUseCase.Command sampleCreateCommand() {
        return new CreateTransactionUseCase.Command(
                USER_ID, "AAPL", TransactionType.BUY, AssetType.COMMON_STOCK, BigDecimal.TEN, BigDecimal.ONE,
                null, Currency.USD, LocalDate.of(2024, 1, 1), null, null, null, null, "NASDAQ", "US", null);
    }

    private static UpdateTransactionUseCase.Command sampleUpdateCommand(UUID id) {
        return new UpdateTransactionUseCase.Command(
                USER_ID, id, "AAPL", null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void givenValidArgs_whenCreateTransaction_thenReturnsMappedDto() {
        Transaction transaction = sampleTransaction();
        CreateTransactionUseCase.Command command = sampleCreateCommand();
        TransactionDto dto = sampleDto(transaction);

        when(mapper.toCreateCommand(USER_ID, "AAPL", TransactionType.BUY, AssetType.COMMON_STOCK,
                BigDecimal.TEN, BigDecimal.ONE, null, Currency.USD, LocalDate.of(2024, 1, 1),
                null, null, null, null, "NASDAQ", "US", null))
                .thenReturn(command);
        when(createTransactionUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new CreateTransactionUseCase.Result.Success(transaction)));
        when(mapper.toDto(transaction)).thenReturn(dto);

        TransactionDto result = tools.createTransaction("AAPL", TransactionType.BUY, AssetType.COMMON_STOCK,
                        BigDecimal.TEN, BigDecimal.ONE, null, Currency.USD, LocalDate.of(2024, 1, 1),
                        null, null, null, null, "NASDAQ", "US", null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(dto, result);
    }

    @Test
    void givenInvalidArgs_whenCreateTransaction_thenFailsWithInvalidRequestCode() {
        CreateTransactionUseCase.Command command = sampleCreateCommand();
        when(mapper.toCreateCommand(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(command);
        when(createTransactionUseCase.execute(command)).thenReturn(
                Uni.createFrom().item(new CreateTransactionUseCase.Result.InvalidRequest("bad quantity")));

        Throwable failure = tools.createTransaction("AAPL", TransactionType.BUY, AssetType.COMMON_STOCK,
                        BigDecimal.TEN, BigDecimal.ONE, null, Currency.USD, LocalDate.of(2024, 1, 1),
                        null, null, null, null, "NASDAQ", "US", null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure instanceof ToolCallException);
        assertEquals("INVALID_REQUEST: bad quantity", failure.getMessage());
    }

    @Test
    void givenDuplicateTransaction_whenCreateTransaction_thenFailsWithConflictCode() {
        CreateTransactionUseCase.Command command = sampleCreateCommand();
        when(mapper.toCreateCommand(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(command);
        when(createTransactionUseCase.execute(command)).thenReturn(
                Uni.createFrom().item(new CreateTransactionUseCase.Result.Conflict("duplicate")));

        Throwable failure = tools.createTransaction("AAPL", TransactionType.BUY, AssetType.COMMON_STOCK,
                        BigDecimal.TEN, BigDecimal.ONE, null, Currency.USD, LocalDate.of(2024, 1, 1),
                        null, null, null, null, "NASDAQ", "US", null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("CONFLICT: duplicate", failure.getMessage());
    }

    @Test
    void givenExistingId_whenGetTransaction_thenReturnsMappedDto() {
        UUID id = UUID.randomUUID();
        Transaction transaction = sampleTransaction();
        TransactionDto dto = sampleDto(transaction);
        when(getTransactionUseCase.execute(new GetTransactionUseCase.Query(USER_ID, id)))
                .thenReturn(Uni.createFrom().item(new GetTransactionUseCase.Result.Success(transaction)));
        when(mapper.toDto(transaction)).thenReturn(dto);

        TransactionDto result = tools.getTransaction(id)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(dto, result);
    }

    @Test
    void givenMissingId_whenGetTransaction_thenFailsWithNotFoundCode() {
        UUID id = UUID.randomUUID();
        when(getTransactionUseCase.execute(new GetTransactionUseCase.Query(USER_ID, id)))
                .thenReturn(Uni.createFrom().item(new GetTransactionUseCase.Result.NotFound(id)));

        Throwable failure = tools.getTransaction(id)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure.getMessage().startsWith("NOT_FOUND"));
        assertTrue(failure.getMessage().contains(id.toString()));
    }

    @Test
    void givenTransactions_whenSearchTransactions_thenReturnsListWithCount() {
        Transaction transaction = sampleTransaction();
        TransactionDto dto = sampleDto(transaction);
        SearchTransactionsUseCase.Query query = new SearchTransactionsUseCase.Query(
                USER_ID, "AAPL", TransactionType.BUY, null, null, TransactionSortOrder.DESC, 10, 0);
        when(searchTransactionsUseCase.execute(query)).thenReturn(
                Uni.createFrom().item(new SearchTransactionsUseCase.Result.Success(List.of(transaction))));
        when(mapper.toDto(transaction)).thenReturn(dto);

        TransactionListDto result = tools.searchTransactions("AAPL", TransactionType.BUY, null, null,
                        TransactionSortOrder.DESC, 10, 0)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(1, result.count());
        assertEquals(dto, result.transactions().get(0));
    }

    @Test
    void givenInvalidRange_whenSearchTransactions_thenFailsWithInvalidRequestCode() {
        SearchTransactionsUseCase.Query query = new SearchTransactionsUseCase.Query(
                USER_ID, null, null, null, null, TransactionSortOrder.ASC, null, null);
        when(searchTransactionsUseCase.execute(query)).thenReturn(
                Uni.createFrom().item(new SearchTransactionsUseCase.Result.InvalidRequest("bad range")));

        Throwable failure = tools.searchTransactions(null, null, null, null, TransactionSortOrder.ASC, null, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("INVALID_REQUEST: bad range", failure.getMessage());
    }

    @Test
    void givenValidArgs_whenUpdateTransaction_thenReturnsMappedDto() {
        UUID id = UUID.randomUUID();
        Transaction transaction = sampleTransaction();
        UpdateTransactionUseCase.Command command = sampleUpdateCommand(id);
        TransactionDto dto = sampleDto(transaction);
        when(mapper.toUpdateCommand(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(command);
        when(updateTransactionUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new UpdateTransactionUseCase.Result.Success(transaction)));
        when(mapper.toDto(transaction)).thenReturn(dto);

        TransactionDto result = tools.updateTransaction(id, "AAPL", null, null, null, null, null, null, null,
                        null, null, null, null, null, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(dto, result);
    }

    @Test
    void givenMissingId_whenUpdateTransaction_thenFailsWithNotFoundCode() {
        UUID id = UUID.randomUUID();
        UpdateTransactionUseCase.Command command = sampleUpdateCommand(id);
        when(mapper.toUpdateCommand(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(command);
        when(updateTransactionUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new UpdateTransactionUseCase.Result.NotFound(id)));

        Throwable failure = tools.updateTransaction(id, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure.getMessage().startsWith("NOT_FOUND"));
    }

    @Test
    void givenInvalidArgs_whenUpdateTransaction_thenFailsWithInvalidRequestCode() {
        UUID id = UUID.randomUUID();
        UpdateTransactionUseCase.Command command = sampleUpdateCommand(id);
        when(mapper.toUpdateCommand(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(command);
        when(updateTransactionUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new UpdateTransactionUseCase.Result.InvalidRequest("bad price")));

        Throwable failure = tools.updateTransaction(id, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("INVALID_REQUEST: bad price", failure.getMessage());
    }

    @Test
    void givenConflictingUpdate_whenUpdateTransaction_thenFailsWithConflictCode() {
        UUID id = UUID.randomUUID();
        UpdateTransactionUseCase.Command command = sampleUpdateCommand(id);
        when(mapper.toUpdateCommand(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(command);
        when(updateTransactionUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new UpdateTransactionUseCase.Result.Conflict("locked")));

        Throwable failure = tools.updateTransaction(id, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("CONFLICT: locked", failure.getMessage());
    }

    @Test
    void givenExistingId_whenDeleteTransaction_thenReturnsConfirmation() {
        UUID id = UUID.randomUUID();
        when(deleteTransactionUseCase.execute(new DeleteTransactionUseCase.Command(USER_ID, id)))
                .thenReturn(Uni.createFrom().item(new DeleteTransactionUseCase.Result.Success(id)));

        DeleteConfirmationDto result = tools.deleteTransaction(id)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertTrue(result.deleted());
        assertEquals(id, result.id());
    }

    @Test
    void givenMissingId_whenDeleteTransaction_thenFailsWithNotFoundCode() {
        UUID id = UUID.randomUUID();
        when(deleteTransactionUseCase.execute(new DeleteTransactionUseCase.Command(USER_ID, id)))
                .thenReturn(Uni.createFrom().item(new DeleteTransactionUseCase.Result.NotFound(id)));

        Throwable failure = tools.deleteTransaction(id)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertTrue(failure.getMessage().startsWith("NOT_FOUND"));
    }

    @Test
    void givenConflictingDelete_whenDeleteTransaction_thenFailsWithConflictCode() {
        UUID id = UUID.randomUUID();
        when(deleteTransactionUseCase.execute(new DeleteTransactionUseCase.Command(USER_ID, id)))
                .thenReturn(Uni.createFrom().item(new DeleteTransactionUseCase.Result.Conflict("would corrupt ledger")));

        Throwable failure = tools.deleteTransaction(id)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("CONFLICT: would corrupt ledger", failure.getMessage());
    }

    @Test
    void givenTicker_whenCountTransactions_thenReturnsCount() {
        when(countTransactionsUseCase.execute(new CountTransactionsUseCase.Query(USER_ID, "AAPL")))
                .thenReturn(Uni.createFrom().item(new CountTransactionsUseCase.Result.Success(7L)));

        TransactionCountDto result = tools.countTransactions("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(7L, result.count());
    }

    @Test
    void givenNoTicker_whenCountTransactions_thenReturnsTotalCount() {
        when(countTransactionsUseCase.execute(new CountTransactionsUseCase.Query(USER_ID, null)))
                .thenReturn(Uni.createFrom().item(new CountTransactionsUseCase.Result.Success(42L)));

        TransactionCountDto result = tools.countTransactions(null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(42L, result.count());
    }
}

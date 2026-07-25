package com.portfolio.adapters.incoming.rest;

import com.portfolio.adapters.incoming.rest.dto.CreateTransactionRequest;
import com.portfolio.adapters.incoming.rest.dto.TransactionResponse;
import com.portfolio.adapters.incoming.rest.dto.UpdateTransactionRequest;
import com.portfolio.adapters.incoming.rest.mapper.TransactionRestMapper;
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
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionControllerTest {

    private static final UserId USER_ID = UserId.of("user-1");

    private final CreateTransactionUseCase createTransactionUseCase = Mockito.mock(CreateTransactionUseCase.class);
    private final UpdateTransactionUseCase updateTransactionUseCase = Mockito.mock(UpdateTransactionUseCase.class);
    private final DeleteTransactionUseCase deleteTransactionUseCase = Mockito.mock(DeleteTransactionUseCase.class);
    private final GetTransactionUseCase getTransactionUseCase = Mockito.mock(GetTransactionUseCase.class);
    private final SearchTransactionsUseCase searchTransactionsUseCase = Mockito.mock(SearchTransactionsUseCase.class);
    private final CountTransactionsUseCase countTransactionsUseCase = Mockito.mock(CountTransactionsUseCase.class);
    private final TransactionRestMapper mapper = Mockito.mock(TransactionRestMapper.class);
    private final UserContext userContext = Mockito.mock(UserContext.class);

    private TransactionController controller;

    @BeforeEach
    void setUp() {
        controller = new TransactionController(
                createTransactionUseCase,
                updateTransactionUseCase,
                deleteTransactionUseCase,
                getTransactionUseCase,
                searchTransactionsUseCase,
                countTransactionsUseCase,
                mapper,
                userContext);
        when(userContext.requireUserId()).thenReturn(USER_ID);
    }

    @Test
    void givenValidCreateRequest_whenCreate_thenReturns201WithMappedBody() {
        CreateTransactionRequest request = sampleCreateRequest();
        CreateTransactionUseCase.Command command = sampleCreateCommand();
        Transaction transaction = sampleTransaction();
        TransactionResponse responseBody = sampleTransactionResponse(transaction);

        when(mapper.toCreateCommand(USER_ID, request)).thenReturn(command);
        when(createTransactionUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new CreateTransactionUseCase.Result.Success(transaction)));
        when(mapper.toResponse(transaction)).thenReturn(responseBody);

        Response response = controller.create(request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        assertEquals(responseBody, response.getEntity());
    }

    @Test
    void givenInvalidCreateRequest_whenCreate_thenReturns400() {
        CreateTransactionRequest request = sampleCreateRequest();
        CreateTransactionUseCase.Command command = sampleCreateCommand();
        CreateTransactionUseCase.Result.InvalidRequest invalid =
                new CreateTransactionUseCase.Result.InvalidRequest("bad request");

        when(mapper.toCreateCommand(USER_ID, request)).thenReturn(command);
        when(createTransactionUseCase.execute(command)).thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.create(request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(invalid, response.getEntity());
    }

    @Test
    void givenExistingTransaction_whenGetById_thenReturns200() {
        UUID id = UUID.randomUUID();
        Transaction transaction = sampleTransaction();
        TransactionResponse responseBody = sampleTransactionResponse(transaction);

        when(getTransactionUseCase.execute(new GetTransactionUseCase.Query(USER_ID, id)))
                .thenReturn(Uni.createFrom().item(new GetTransactionUseCase.Result.Success(transaction)));
        when(mapper.toResponse(transaction)).thenReturn(responseBody);

        Response response = controller.getById(id)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(responseBody, response.getEntity());
    }

    @Test
    void givenMissingTransaction_whenGetById_thenReturns404() {
        UUID id = UUID.randomUUID();

        when(getTransactionUseCase.execute(new GetTransactionUseCase.Query(USER_ID, id)))
                .thenReturn(Uni.createFrom().item(new GetTransactionUseCase.Result.NotFound(id)));

        Response response = controller.getById(id)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void givenTransactions_whenListAll_thenReturns200WithMappedList() {
        Transaction transaction = sampleTransaction();
        TransactionResponse responseBody = sampleTransactionResponse(transaction);

        when(searchTransactionsUseCase.execute(SearchTransactionsUseCase.Query.all(USER_ID)))
                .thenReturn(Uni.createFrom().item(new SearchTransactionsUseCase.Result.Success(List.of(transaction))));
        when(mapper.toResponse(transaction)).thenReturn(responseBody);

        Response response = controller.listAll()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(List.of(responseBody), response.getEntity());
    }

    @Test
    void givenTransactions_whenListByTicker_thenReturns200WithMappedList() {
        Transaction transaction = sampleTransaction();
        TransactionResponse responseBody = sampleTransactionResponse(transaction);

        when(searchTransactionsUseCase.execute(SearchTransactionsUseCase.Query.byTicker(USER_ID, "AAPL")))
                .thenReturn(Uni.createFrom().item(new SearchTransactionsUseCase.Result.Success(List.of(transaction))));
        when(mapper.toResponse(transaction)).thenReturn(responseBody);

        Response response = controller.listByTicker("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(List.of(responseBody), response.getEntity());
    }

    @Test
    void givenSearchCriteria_whenSearch_thenReturns200WithMappedList() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 12, 31);
        Transaction transaction = sampleTransaction();
        TransactionResponse responseBody = sampleTransactionResponse(transaction);
        SearchTransactionsUseCase.Query query = new SearchTransactionsUseCase.Query(
                USER_ID, "AAPL", TransactionType.BUY, from, to, TransactionSortOrder.ASC, null, null);

        when(searchTransactionsUseCase.execute(query))
                .thenReturn(Uni.createFrom().item(new SearchTransactionsUseCase.Result.Success(List.of(transaction))));
        when(mapper.toResponse(transaction)).thenReturn(responseBody);

        Response response = controller.search("AAPL", TransactionType.BUY, from, to, TransactionSortOrder.ASC, null, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(List.of(responseBody), response.getEntity());
    }

    @Test
    void givenInvalidPagination_whenSearch_thenReturns400() {
        SearchTransactionsUseCase.Result.InvalidRequest invalid =
                new SearchTransactionsUseCase.Result.InvalidRequest("limit must be positive");
        when(searchTransactionsUseCase.execute(new SearchTransactionsUseCase.Query(
                        USER_ID, null, null, null, null, TransactionSortOrder.DESC, -1, null)))
                .thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.search(null, null, null, null, TransactionSortOrder.DESC, -1, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(invalid, response.getEntity());
    }

    @Test
    void givenInvalidRequest_whenListAll_thenReturns400() {
        SearchTransactionsUseCase.Result.InvalidRequest invalid =
                new SearchTransactionsUseCase.Result.InvalidRequest("bad request");
        when(searchTransactionsUseCase.execute(SearchTransactionsUseCase.Query.all(USER_ID)))
                .thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.listAll()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    }

    @Test
    void givenInvalidRequest_whenListByTicker_thenReturns400() {
        SearchTransactionsUseCase.Result.InvalidRequest invalid =
                new SearchTransactionsUseCase.Result.InvalidRequest("bad request");
        when(searchTransactionsUseCase.execute(SearchTransactionsUseCase.Query.byTicker(USER_ID, "AAPL")))
                .thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.listByTicker("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
    }

    @Test
    void givenValidUpdate_whenUpdate_thenReturns200() {
        UUID id = UUID.randomUUID();
        UpdateTransactionRequest request = sampleUpdateRequest();
        UpdateTransactionUseCase.Command command = sampleUpdateCommand(id);
        Transaction transaction = sampleTransaction();
        TransactionResponse responseBody = sampleTransactionResponse(transaction);

        when(mapper.toUpdateCommand(USER_ID, id, request)).thenReturn(command);
        when(updateTransactionUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new UpdateTransactionUseCase.Result.Success(transaction)));
        when(mapper.toResponse(transaction)).thenReturn(responseBody);

        Response response = controller.update(id, request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(responseBody, response.getEntity());
    }

    @Test
    void givenMissingTransaction_whenUpdate_thenReturns404() {
        UUID id = UUID.randomUUID();
        UpdateTransactionRequest request = sampleUpdateRequest();
        UpdateTransactionUseCase.Command command = sampleUpdateCommand(id);

        when(mapper.toUpdateCommand(USER_ID, id, request)).thenReturn(command);
        when(updateTransactionUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new UpdateTransactionUseCase.Result.NotFound(id)));

        Response response = controller.update(id, request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void givenInvalidUpdate_whenUpdate_thenReturns400() {
        UUID id = UUID.randomUUID();
        UpdateTransactionRequest request = sampleUpdateRequest();
        UpdateTransactionUseCase.Command command = sampleUpdateCommand(id);
        UpdateTransactionUseCase.Result.InvalidRequest invalid =
                new UpdateTransactionUseCase.Result.InvalidRequest("invalid update");

        when(mapper.toUpdateCommand(USER_ID, id, request)).thenReturn(command);
        when(updateTransactionUseCase.execute(command)).thenReturn(Uni.createFrom().item(invalid));

        Response response = controller.update(id, request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(invalid, response.getEntity());
    }

    @Test
    void givenExistingTransaction_whenDelete_thenReturns204() {
        UUID id = UUID.randomUUID();

        when(deleteTransactionUseCase.execute(new DeleteTransactionUseCase.Command(USER_ID, id)))
                .thenReturn(Uni.createFrom().item(new DeleteTransactionUseCase.Result.Success(id)));

        Response response = controller.delete(id)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
    }

    @Test
    void givenMissingTransaction_whenDelete_thenReturns404() {
        UUID id = UUID.randomUUID();

        when(deleteTransactionUseCase.execute(new DeleteTransactionUseCase.Command(USER_ID, id)))
                .thenReturn(Uni.createFrom().item(new DeleteTransactionUseCase.Result.NotFound(id)));

        Response response = controller.delete(id)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void givenTransactions_whenCount_thenReturnsLong() {
        when(countTransactionsUseCase.execute(CountTransactionsUseCase.Query.all(USER_ID)))
                .thenReturn(Uni.createFrom().item(new CountTransactionsUseCase.Result.Success(7L)));

        Long count = controller.count()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(7L, count);
    }

    @Test
    void givenTransactions_whenCountByTicker_thenReturnsLong() {
        when(countTransactionsUseCase.execute(CountTransactionsUseCase.Query.byTicker(USER_ID, "AAPL")))
                .thenReturn(Uni.createFrom().item(new CountTransactionsUseCase.Result.Success(3L)));

        Long count = controller.countByTicker("AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(3L, count);
    }

    @Test
    void givenCreateRequest_whenCreate_thenUsesUserIdFromContext() {
        CreateTransactionRequest request = sampleCreateRequest();
        CreateTransactionUseCase.Command command = sampleCreateCommand();

        when(mapper.toCreateCommand(USER_ID, request)).thenReturn(command);
        when(createTransactionUseCase.execute(command))
                .thenReturn(Uni.createFrom().item(new CreateTransactionUseCase.Result.InvalidRequest("x")));

        controller.create(request)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        verify(userContext).requireUserId();
        verify(mapper).toCreateCommand(eq(USER_ID), eq(request));
    }

    private static CreateTransactionRequest sampleCreateRequest() {
        return new CreateTransactionRequest(
                "AAPL",
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
                "Apple Inc.");
    }

    private static CreateTransactionUseCase.Command sampleCreateCommand() {
        return new CreateTransactionUseCase.Command(
                USER_ID,
                "AAPL",
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
                "Apple Inc.");
    }

    private static UpdateTransactionRequest sampleUpdateRequest() {
        return new UpdateTransactionRequest(
                "AAPL",
                TransactionType.SELL,
                new BigDecimal("5"),
                new BigDecimal("110"),
                BigDecimal.ZERO,
                Currency.USD,
                LocalDate.of(2024, 2, 1),
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "Apple Inc.");
    }

    private static UpdateTransactionUseCase.Command sampleUpdateCommand(UUID id) {
        return new UpdateTransactionUseCase.Command(
                USER_ID,
                id,
                "AAPL",
                TransactionType.SELL,
                new BigDecimal("5"),
                new BigDecimal("110"),
                BigDecimal.ZERO,
                Currency.USD,
                LocalDate.of(2024, 2, 1),
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "Apple Inc.");
    }

    private static Transaction sampleTransaction() {
        return new Transaction(
                UUID.randomUUID(),
                USER_ID,
                "AAPL",
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
                "Apple Inc.",
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));
    }

    private static TransactionResponse sampleTransactionResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.id(),
                transaction.ticker(),
                transaction.transactionType(),
                transaction.assetType(),
                transaction.quantity(),
                transaction.price(),
                transaction.fees(),
                transaction.currency(),
                transaction.transactionDate(),
                transaction.notes(),
                transaction.fractional(),
                transaction.fractionalMultiplier(),
                transaction.commissionCurrency(),
                transaction.exchange(),
                transaction.country(),
                transaction.companyName(),
                transaction.totalValue(),
                transaction.totalCost(),
                transaction.createdAt(),
                transaction.updatedAt());
    }
}

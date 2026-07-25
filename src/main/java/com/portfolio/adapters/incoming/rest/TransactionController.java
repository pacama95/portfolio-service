package com.portfolio.adapters.incoming.rest;

import com.portfolio.adapters.incoming.rest.dto.CreateTransactionRequest;
import com.portfolio.adapters.incoming.rest.dto.TransactionResponse;
import com.portfolio.adapters.incoming.rest.dto.UpdateTransactionRequest;
import com.portfolio.adapters.incoming.rest.mapper.TransactionRestMapper;
import com.portfolio.core.model.TransactionSortOrder;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.ports.incoming.CountTransactionsUseCase;
import com.portfolio.core.ports.incoming.CreateTransactionUseCase;
import com.portfolio.core.ports.incoming.DeleteTransactionUseCase;
import com.portfolio.core.ports.incoming.GetTransactionUseCase;
import com.portfolio.core.ports.incoming.SearchTransactionsUseCase;
import com.portfolio.core.ports.incoming.UpdateTransactionUseCase;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.UUID;

@Path("/api/transactions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Transactions", description = "Per-user transaction ledger")
public class TransactionController {

    private static final Logger LOG = Logger.getLogger(TransactionController.class);

    private final CreateTransactionUseCase createTransactionUseCase;
    private final UpdateTransactionUseCase updateTransactionUseCase;
    private final DeleteTransactionUseCase deleteTransactionUseCase;
    private final GetTransactionUseCase getTransactionUseCase;
    private final SearchTransactionsUseCase searchTransactionsUseCase;
    private final CountTransactionsUseCase countTransactionsUseCase;
    private final TransactionRestMapper mapper;
    private final UserContext userContext;

    public TransactionController(
            CreateTransactionUseCase createTransactionUseCase,
            UpdateTransactionUseCase updateTransactionUseCase,
            DeleteTransactionUseCase deleteTransactionUseCase,
            GetTransactionUseCase getTransactionUseCase,
            SearchTransactionsUseCase searchTransactionsUseCase,
            CountTransactionsUseCase countTransactionsUseCase,
            TransactionRestMapper mapper,
            UserContext userContext) {
        this.createTransactionUseCase = createTransactionUseCase;
        this.updateTransactionUseCase = updateTransactionUseCase;
        this.deleteTransactionUseCase = deleteTransactionUseCase;
        this.getTransactionUseCase = getTransactionUseCase;
        this.searchTransactionsUseCase = searchTransactionsUseCase;
        this.countTransactionsUseCase = countTransactionsUseCase;
        this.mapper = mapper;
        this.userContext = userContext;
    }

    @POST
    @Operation(summary = "Create a transaction")
    @Parameter(name = UserIdentityFilter.USER_ID_HEADER, in = ParameterIn.HEADER, required = true)
    public Uni<Response> create(@Valid CreateTransactionRequest request) {
        return createTransactionUseCase.execute(mapper.toCreateCommand(userContext.requireUserId(), request))
                .map(result -> switch (result) {
                    case CreateTransactionUseCase.Result.Success success ->
                            Response.status(Response.Status.CREATED).entity(mapper.toResponse(success.transaction())).build();
                    case CreateTransactionUseCase.Result.InvalidRequest invalid -> {
                        LOG.warnf("Create transaction rejected reason=%s", invalid.message());
                        yield Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                    }
                    case CreateTransactionUseCase.Result.Conflict conflict -> {
                        LOG.warnf("Create transaction conflict reason=%s", conflict.message());
                        yield Response.status(Response.Status.CONFLICT).entity(conflict).build();
                    }
                });
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a transaction by id")
    public Uni<Response> getById(@PathParam("id") UUID id) {
        return getTransactionUseCase.execute(new GetTransactionUseCase.Query(userContext.requireUserId(), id))
                .map(result -> switch (result) {
                    case GetTransactionUseCase.Result.Success success ->
                            Response.ok(mapper.toResponse(success.transaction())).build();
                    case GetTransactionUseCase.Result.NotFound ignored -> {
                        LOG.warnf("Transaction not found id=%s", id);
                        yield Response.status(Response.Status.NOT_FOUND).build();
                    }
                });
    }

    @GET
    @Operation(summary = "List all transactions for the user")
    public Uni<Response> listAll() {
        return searchTransactionsUseCase.execute(SearchTransactionsUseCase.Query.all(userContext.requireUserId()))
                .map(result -> switch (result) {
                    case SearchTransactionsUseCase.Result.Success success ->
                            Response.ok(success.transactions().stream().map(mapper::toResponse).toList()).build();
                    case SearchTransactionsUseCase.Result.InvalidRequest invalid ->
                            Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                });
    }

    @GET
    @Path("/ticker/{ticker}")
    @Operation(summary = "List transactions by ticker")
    public Uni<Response> listByTicker(@PathParam("ticker") String ticker) {
        return searchTransactionsUseCase.execute(
                        SearchTransactionsUseCase.Query.byTicker(userContext.requireUserId(), ticker))
                .map(result -> switch (result) {
                    case SearchTransactionsUseCase.Result.Success success ->
                            Response.ok(success.transactions().stream().map(mapper::toResponse).toList()).build();
                    case SearchTransactionsUseCase.Result.InvalidRequest invalid ->
                            Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                });
    }

    @GET
    @Path("/search")
    @Operation(summary = "Search transactions")
    public Uni<Response> search(
            @QueryParam("ticker") String ticker,
            @QueryParam("type") TransactionType type,
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sort") @DefaultValue("DESC") TransactionSortOrder sort,
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset) {
        return searchTransactionsUseCase.execute(new SearchTransactionsUseCase.Query(
                        userContext.requireUserId(), ticker, type, fromDate, toDate, sort, limit, offset))
                .map(result -> switch (result) {
                    case SearchTransactionsUseCase.Result.Success success ->
                            Response.ok(success.transactions().stream().map(mapper::toResponse).toList()).build();
                    case SearchTransactionsUseCase.Result.InvalidRequest invalid -> {
                        LOG.warnf("Search transactions rejected reason=%s", invalid.message());
                        yield Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                    }
                });
    }

    @PUT
    @Path("/{id}")
    @Operation(summary = "Update a transaction in place")
    public Uni<Response> update(@PathParam("id") UUID id, @Valid UpdateTransactionRequest request) {
        return updateTransactionUseCase.execute(mapper.toUpdateCommand(userContext.requireUserId(), id, request))
                .map(result -> switch (result) {
                    case UpdateTransactionUseCase.Result.Success success ->
                            Response.ok(mapper.toResponse(success.transaction())).build();
                    case UpdateTransactionUseCase.Result.NotFound ignored -> {
                        LOG.warnf("Update transaction not found id=%s", id);
                        yield Response.status(Response.Status.NOT_FOUND).build();
                    }
                    case UpdateTransactionUseCase.Result.InvalidRequest invalid -> {
                        LOG.warnf("Update transaction rejected id=%s reason=%s", id, invalid.message());
                        yield Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                    }
                    case UpdateTransactionUseCase.Result.Conflict conflict -> {
                        LOG.warnf("Update transaction conflict id=%s reason=%s", id, conflict.message());
                        yield Response.status(Response.Status.CONFLICT).entity(conflict).build();
                    }
                });
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete a transaction")
    public Uni<Response> delete(@PathParam("id") UUID id) {
        return deleteTransactionUseCase.execute(
                        new DeleteTransactionUseCase.Command(userContext.requireUserId(), id))
                .map(result -> switch (result) {
                    case DeleteTransactionUseCase.Result.Success ignored ->
                            Response.noContent().build();
                    case DeleteTransactionUseCase.Result.NotFound ignored -> {
                        LOG.warnf("Delete transaction not found id=%s", id);
                        yield Response.status(Response.Status.NOT_FOUND).build();
                    }
                    case DeleteTransactionUseCase.Result.Conflict conflict -> {
                        LOG.warnf("Delete transaction conflict id=%s reason=%s", id, conflict.message());
                        yield Response.status(Response.Status.CONFLICT).entity(conflict).build();
                    }
                });
    }

    @GET
    @Path("/count")
    @Operation(summary = "Count all transactions for the user")
    public Uni<Long> count() {
        return countTransactionsUseCase.execute(CountTransactionsUseCase.Query.all(userContext.requireUserId()))
                .map(result -> switch (result) {
                    case CountTransactionsUseCase.Result.Success success -> success.count();
                });
    }

    @GET
    @Path("/count/{ticker}")
    @Operation(summary = "Count transactions for a ticker")
    public Uni<Long> countByTicker(@PathParam("ticker") String ticker) {
        return countTransactionsUseCase.execute(
                        CountTransactionsUseCase.Query.byTicker(userContext.requireUserId(), ticker))
                .map(result -> switch (result) {
                    case CountTransactionsUseCase.Result.Success success -> success.count();
                });
    }

}

package com.portfolio.adapters.incoming.rest;

import com.portfolio.adapters.incoming.rest.dto.UpdateMarketDataRequest;
import com.portfolio.adapters.incoming.rest.mapper.PositionRestMapper;
import com.portfolio.core.ports.incoming.GetPositionByTickerUseCase;
import com.portfolio.core.ports.incoming.GetPositionsUseCase;
import com.portfolio.core.ports.incoming.UpdateMarketPriceUseCase;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/positions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Positions", description = "Positions derived on read from the transaction ledger")
public class PositionController {

    private static final Logger LOG = Logger.getLogger(PositionController.class);

    private final GetPositionsUseCase getPositionsUseCase;
    private final GetPositionByTickerUseCase getPositionByTickerUseCase;
    private final UpdateMarketPriceUseCase updateMarketPriceUseCase;
    private final PositionRestMapper mapper;
    private final UserContext userContext;

    public PositionController(
            GetPositionsUseCase getPositionsUseCase,
            GetPositionByTickerUseCase getPositionByTickerUseCase,
            UpdateMarketPriceUseCase updateMarketPriceUseCase,
            PositionRestMapper mapper,
            UserContext userContext) {
        this.getPositionsUseCase = getPositionsUseCase;
        this.getPositionByTickerUseCase = getPositionByTickerUseCase;
        this.updateMarketPriceUseCase = updateMarketPriceUseCase;
        this.mapper = mapper;
        this.userContext = userContext;
    }

    @GET
    @Operation(summary = "List all positions for the user")
    @Parameter(name = UserIdentityFilter.USER_ID_HEADER, in = ParameterIn.HEADER, required = true)
    public Uni<Response> listAll() {
        return getPositionsUseCase.execute(GetPositionsUseCase.Query.all(userContext.requireUserId()))
                .map(result -> switch (result) {
                    case GetPositionsUseCase.Result.Success success ->
                            Response.ok(success.positions().stream().map(mapper::toResponse).toList()).build();
                });
    }

    @GET
    @Path("/active")
    @Operation(summary = "List active positions for the user")
    public Uni<Response> listActive() {
        return getPositionsUseCase.execute(GetPositionsUseCase.Query.active(userContext.requireUserId()))
                .map(result -> switch (result) {
                    case GetPositionsUseCase.Result.Success success ->
                            Response.ok(success.positions().stream().map(mapper::toResponse).toList()).build();
                });
    }

    @GET
    @Path("/ticker/{ticker}")
    @Operation(summary = "Get position by ticker")
    public Uni<Response> getByTicker(@PathParam("ticker") String ticker) {
        return getPositionByTickerUseCase.execute(
                        new GetPositionByTickerUseCase.Query(userContext.requireUserId(), ticker))
                .map(result -> switch (result) {
                    case GetPositionByTickerUseCase.Result.Success success ->
                            Response.ok(mapper.toResponse(success.position())).build();
                    case GetPositionByTickerUseCase.Result.NotFound ignored -> {
                        LOG.warnf("Position not found ticker=%s", ticker);
                        yield Response.status(Response.Status.NOT_FOUND).build();
                    }
                });
    }

    @GET
    @Path("/ticker/{ticker}/exists")
    @Operation(summary = "Check whether a position exists for ticker")
    public Uni<Boolean> exists(@PathParam("ticker") String ticker) {
        return getPositionByTickerUseCase.execute(
                        new GetPositionByTickerUseCase.Query(userContext.requireUserId(), ticker))
                .map(result -> result instanceof GetPositionByTickerUseCase.Result.Success);
    }

    @GET
    @Path("/count")
    @Operation(summary = "Count all positions")
    public Uni<Long> count() {
        return getPositionsUseCase.execute(GetPositionsUseCase.Query.all(userContext.requireUserId()))
                .map(result -> switch (result) {
                    case GetPositionsUseCase.Result.Success success -> (long) success.positions().size();
                });
    }

    @GET
    @Path("/count/active")
    @Operation(summary = "Count active positions")
    public Uni<Long> countActive() {
        return getPositionsUseCase.execute(GetPositionsUseCase.Query.active(userContext.requireUserId()))
                .map(result -> switch (result) {
                    case GetPositionsUseCase.Result.Success success -> (long) success.positions().size();
                });
    }

    @PUT
    @Path("/ticker/{ticker}/price")
    @Operation(summary = "Manually set the latest market price for a ticker")
    public Uni<Response> updatePrice(@PathParam("ticker") String ticker, @Valid UpdateMarketDataRequest request) {
        return updateMarketPriceUseCase.execute(new UpdateMarketPriceUseCase.Command(
                        userContext.requireUserId(), ticker, request.price(), request.currency()))
                .map(result -> switch (result) {
                    case UpdateMarketPriceUseCase.Result.Success success ->
                            Response.ok(mapper.toResponse(success.position())).build();
                    case UpdateMarketPriceUseCase.Result.NotFound ignored -> {
                        LOG.warnf("Update market price not found ticker=%s", ticker);
                        yield Response.status(Response.Status.NOT_FOUND).build();
                    }
                    case UpdateMarketPriceUseCase.Result.InvalidRequest invalid -> {
                        LOG.warnf("Update market price rejected ticker=%s reason=%s", ticker, invalid.message());
                        yield Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                    }
                });
    }
}

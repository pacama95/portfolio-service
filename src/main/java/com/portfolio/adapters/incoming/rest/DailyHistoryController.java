package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.ports.incoming.GetCapitalFlowsUseCase;
import com.portfolio.core.ports.incoming.GetDailyPositionHistoryUseCase;
import com.portfolio.core.ports.incoming.GetPerformanceInputsUseCase;
import com.portfolio.core.ports.incoming.GetPortfolioValuationHistoryUseCase;
import com.portfolio.core.ports.incoming.GetPriceHistoryUseCase;
import com.portfolio.util.SymbolParser;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
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
import java.util.Map;

@Path("/api/daily-history")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Daily History", description = "Historical positions, prices, valuation and capital flows")
public class DailyHistoryController {

    private static final Logger LOG = Logger.getLogger(DailyHistoryController.class);

    private final GetDailyPositionHistoryUseCase dailyPositionHistoryUseCase;
    private final GetPriceHistoryUseCase priceHistoryUseCase;
    private final GetPortfolioValuationHistoryUseCase valuationHistoryUseCase;
    private final GetCapitalFlowsUseCase capitalFlowsUseCase;
    private final GetPerformanceInputsUseCase performanceInputsUseCase;
    private final UserContext userContext;

    public DailyHistoryController(
            GetDailyPositionHistoryUseCase dailyPositionHistoryUseCase,
            GetPriceHistoryUseCase priceHistoryUseCase,
            GetPortfolioValuationHistoryUseCase valuationHistoryUseCase,
            GetCapitalFlowsUseCase capitalFlowsUseCase,
            GetPerformanceInputsUseCase performanceInputsUseCase,
            UserContext userContext) {
        this.dailyPositionHistoryUseCase = dailyPositionHistoryUseCase;
        this.priceHistoryUseCase = priceHistoryUseCase;
        this.valuationHistoryUseCase = valuationHistoryUseCase;
        this.capitalFlowsUseCase = capitalFlowsUseCase;
        this.performanceInputsUseCase = performanceInputsUseCase;
        this.userContext = userContext;
    }

    @GET
    @Path("/positions")
    @Operation(summary = "Daily position snapshots")
    @Parameter(name = UserIdentityFilter.USER_ID_HEADER, in = ParameterIn.HEADER, required = true)
    public Uni<Response> positions(
            @QueryParam("from") LocalDate from,
            @QueryParam("to") LocalDate to,
            @QueryParam("ticker") String ticker) {
        return dailyPositionHistoryUseCase.execute(
                        new GetDailyPositionHistoryUseCase.Query(userContext.requireUserId(), from, to, ticker))
                .map(result -> switch (result) {
                    case GetDailyPositionHistoryUseCase.Result.Success success ->
                            Response.ok(success.snapshots()).build();
                    case GetDailyPositionHistoryUseCase.Result.InvalidRequest invalid -> {
                        LOG.warnf("Daily position history rejected reason=%s", invalid.message());
                        yield Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                    }
                    case GetDailyPositionHistoryUseCase.Result.Conflict conflict -> {
                        LOG.warnf("Daily position history conflict reason=%s", conflict.message());
                        yield Response.status(Response.Status.CONFLICT).entity(conflict).build();
                    }
                });
    }

    @GET
    @Path("/prices")
    @Operation(summary = "Price history for symbols")
    public Uni<Response> prices(
            @QueryParam("symbols") String symbols,
            @QueryParam("from") LocalDate from,
            @QueryParam("to") LocalDate to) {
        return priceHistoryUseCase.execute(new GetPriceHistoryUseCase.Query(
                        userContext.requireUserId(),
                        SymbolParser.parseCsv(symbols),
                        from,
                        to))
                .map(result -> switch (result) {
                    case GetPriceHistoryUseCase.Result.Success success ->
                            Response.ok(success.entries()).build();
                    case GetPriceHistoryUseCase.Result.InvalidRequest invalid -> {
                        LOG.warnf("Price history rejected reason=%s", invalid.message());
                        yield Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                    }
                });
    }

    @GET
    @Path("/portfolio/valuation")
    @Operation(summary = "Daily portfolio valuation history")
    public Uni<Response> valuation(@QueryParam("from") LocalDate from, @QueryParam("to") LocalDate to) {
        return valuationHistoryUseCase.execute(
                        new GetPortfolioValuationHistoryUseCase.Query(userContext.requireUserId(), from, to))
                .map(result -> switch (result) {
                    case GetPortfolioValuationHistoryUseCase.Result.Success success ->
                            Response.ok(success.valuations()).build();
                    case GetPortfolioValuationHistoryUseCase.Result.InvalidRequest invalid -> {
                        LOG.warnf("Valuation history rejected reason=%s", invalid.message());
                        yield Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                    }
                    case GetPortfolioValuationHistoryUseCase.Result.Conflict conflict -> {
                        LOG.warnf("Valuation history conflict reason=%s", conflict.message());
                        yield Response.status(Response.Status.CONFLICT).entity(conflict).build();
                    }
                });
    }

    @GET
    @Path("/portfolio/capital-flows")
    @Operation(summary = "Capital flows projected from the ledger")
    public Uni<Response> capitalFlows(@QueryParam("from") LocalDate from, @QueryParam("to") LocalDate to) {
        return capitalFlowsUseCase.execute(
                        new GetCapitalFlowsUseCase.Query(userContext.requireUserId(), from, to))
                .map(result -> switch (result) {
                    case GetCapitalFlowsUseCase.Result.Success success ->
                            Response.ok(Map.of("flows", success.flows())).build();
                    case GetCapitalFlowsUseCase.Result.InvalidRequest invalid -> {
                        LOG.warnf("Capital flows rejected reason=%s", invalid.message());
                        yield Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                    }
                });
    }

    @GET
    @Path("/portfolio/performance-inputs")
    @Operation(summary = "Bundled valuation + capital flows for quant performance")
    public Uni<Response> performanceInputs(@QueryParam("from") LocalDate from, @QueryParam("to") LocalDate to) {
        return performanceInputsUseCase.execute(
                        new GetPerformanceInputsUseCase.Query(userContext.requireUserId(), from, to))
                .map(result -> switch (result) {
                    case GetPerformanceInputsUseCase.Result.Success success ->
                            Response.ok(success.inputs()).build();
                    case GetPerformanceInputsUseCase.Result.InvalidRequest invalid -> {
                        LOG.warnf("Performance inputs rejected reason=%s", invalid.message());
                        yield Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                    }
                    case GetPerformanceInputsUseCase.Result.Conflict conflict -> {
                        LOG.warnf("Performance inputs conflict reason=%s", conflict.message());
                        yield Response.status(Response.Status.CONFLICT).entity(conflict).build();
                    }
                });
    }
}

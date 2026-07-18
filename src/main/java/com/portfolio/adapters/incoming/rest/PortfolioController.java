package com.portfolio.adapters.incoming.rest;

import com.portfolio.adapters.incoming.rest.mapper.PositionRestMapper;
import com.portfolio.core.ports.incoming.GetPortfolioSummaryUseCase;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/portfolio")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Portfolio", description = "Portfolio summary derived from the ledger")
public class PortfolioController {

    private final GetPortfolioSummaryUseCase getPortfolioSummaryUseCase;
    private final PositionRestMapper mapper;
    private final UserContext userContext;

    public PortfolioController(
            GetPortfolioSummaryUseCase getPortfolioSummaryUseCase,
            PositionRestMapper mapper,
            UserContext userContext) {
        this.getPortfolioSummaryUseCase = getPortfolioSummaryUseCase;
        this.mapper = mapper;
        this.userContext = userContext;
    }

    @GET
    @Path("/summary")
    @Operation(summary = "Portfolio summary for all positions")
    @Parameter(name = UserIdentityFilter.USER_ID_HEADER, in = ParameterIn.HEADER, required = true)
    public Uni<Response> summary() {
        return getPortfolioSummaryUseCase.execute(
                        GetPortfolioSummaryUseCase.Query.all(userContext.requireUserId()))
                .map(result -> switch (result) {
                    case GetPortfolioSummaryUseCase.Result.Success success ->
                            Response.ok(mapper.toResponse(success.summary())).build();
                });
    }

    @GET
    @Path("/summary/active")
    @Operation(summary = "Portfolio summary for active positions")
    public Uni<Response> summaryActive() {
        return getPortfolioSummaryUseCase.execute(
                        GetPortfolioSummaryUseCase.Query.active(userContext.requireUserId()))
                .map(result -> switch (result) {
                    case GetPortfolioSummaryUseCase.Result.Success success ->
                            Response.ok(mapper.toResponse(success.summary())).build();
                });
    }
}

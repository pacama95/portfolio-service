package com.portfolio.adapters.incoming.rest;

import com.portfolio.core.ports.incoming.GetPriceIngestionRunUseCase;
import com.portfolio.core.ports.incoming.TriggerPriceIngestionUseCase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.Map;

@Path("/api/price-ingestion")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Price Ingestion", description = "Proactive market data ingestion")
public class PriceIngestionController {

    private static final Logger LOG = Logger.getLogger(PriceIngestionController.class);

    private final TriggerPriceIngestionUseCase triggerPriceIngestionUseCase;
    private final GetPriceIngestionRunUseCase getPriceIngestionRunUseCase;
    private final UserContext userContext;

    public PriceIngestionController(
            TriggerPriceIngestionUseCase triggerPriceIngestionUseCase,
            GetPriceIngestionRunUseCase getPriceIngestionRunUseCase,
            UserContext userContext) {
        this.triggerPriceIngestionUseCase = triggerPriceIngestionUseCase;
        this.getPriceIngestionRunUseCase = getPriceIngestionRunUseCase;
        this.userContext = userContext;
    }

    @RegisterForReflection
    public record TriggerPriceIngestionRequest(
            LocalDate from,
            LocalDate to,
            String ticker,
            Boolean includeBackfill
    ) {
    }

    @POST
    @Path("/runs")
    @Operation(summary = "Trigger a price history ingestion run")
    @Parameter(name = UserIdentityFilter.USER_ID_HEADER, in = ParameterIn.HEADER, required = true)
    public Uni<Response> trigger(TriggerPriceIngestionRequest request) {
        return triggerPriceIngestionUseCase.execute(new TriggerPriceIngestionUseCase.Command(
                        userContext.requireUserId(),
                        request.from(),
                        request.to(),
                        request.ticker(),
                        Boolean.TRUE.equals(request.includeBackfill())))
                .map(result -> switch (result) {
                    case TriggerPriceIngestionUseCase.Result.Accepted accepted ->
                            Response.accepted(Map.of("runId", accepted.runId())).build();
                    case TriggerPriceIngestionUseCase.Result.Conflict conflict -> {
                        LOG.warnf("Price ingestion conflict reason=%s", conflict.message());
                        yield Response.status(Response.Status.CONFLICT).entity(conflict).build();
                    }
                    case TriggerPriceIngestionUseCase.Result.InvalidRequest invalid -> {
                        LOG.warnf("Price ingestion rejected reason=%s", invalid.message());
                        yield Response.status(Response.Status.BAD_REQUEST).entity(invalid).build();
                    }
                });
    }

    @GET
    @Path("/runs/latest")
    @Operation(summary = "Get the latest ingestion run status")
    public Uni<Response> latest() {
        return getPriceIngestionRunUseCase.execute(
                        new GetPriceIngestionRunUseCase.Query(userContext.requireUserId()))
                .map(result -> switch (result) {
                    case GetPriceIngestionRunUseCase.Result.Success success ->
                            Response.ok(success.status()).build();
                    case GetPriceIngestionRunUseCase.Result.NotFound ignored -> {
                        LOG.warn("Latest price ingestion run not found");
                        yield Response.status(Response.Status.NOT_FOUND).build();
                    }
                });
    }
}

package com.portfolio.adapters.outgoing.marketdata.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "twelve-data-api")
@Path("/")
public interface TwelveDataClient {

    @GET
    @Path("/price")
    Uni<JsonNode> price(@QueryParam("symbol") String symbol, @QueryParam("apikey") String apiKey);

    @GET
    @Path("/exchange_rate")
    Uni<JsonNode> exchangeRate(
            @QueryParam("symbol") String symbol,
            @QueryParam("apikey") String apiKey);

    @GET
    @Path("/time_series")
    Uni<JsonNode> timeSeries(
            @QueryParam("symbol") String symbol,
            @QueryParam("interval") String interval,
            @QueryParam("start_date") String startDate,
            @QueryParam("end_date") String endDate,
            @QueryParam("apikey") String apiKey);
}

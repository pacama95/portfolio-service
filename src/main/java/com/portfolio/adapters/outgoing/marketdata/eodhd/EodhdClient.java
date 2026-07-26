package com.portfolio.adapters.outgoing.marketdata.eodhd;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "eodhd-api")
@Path("/api")
public interface EodhdClient {

    @GET
    @Path("/exchanges-list/")
    Uni<List<EodhdExchangeResponse>> exchanges(
            @QueryParam("api_token") String apiToken,
            @QueryParam("fmt") String format);

    @GET
    @Path("/search/{query}")
    Uni<List<EodhdSearchResponse>> search(
            @PathParam("query") String query,
            @QueryParam("api_token") String apiToken,
            @QueryParam("fmt") String format,
            @QueryParam("exchange") String exchange);

    @GET
    @Path("/real-time/{ticker}")
    Uni<EodhdRealTimeResponse> realTime(
            @PathParam("ticker") String ticker,
            @QueryParam("api_token") String apiToken,
            @QueryParam("fmt") String format);

    @GET
    @Path("/eod/{ticker}")
    Uni<List<EodhdEodBarResponse>> eod(
            @PathParam("ticker") String ticker,
            @QueryParam("api_token") String apiToken,
            @QueryParam("fmt") String format,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("period") String period,
            @QueryParam("order") String order);
}

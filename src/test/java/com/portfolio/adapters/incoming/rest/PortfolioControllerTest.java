package com.portfolio.adapters.incoming.rest;

import com.portfolio.adapters.incoming.rest.dto.PortfolioSummaryResponse;
import com.portfolio.adapters.incoming.rest.mapper.PositionRestMapper;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.PortfolioSummary;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPortfolioSummaryUseCase;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class PortfolioControllerTest {

    private static final UserId USER_ID = UserId.of("user-1");

    private final GetPortfolioSummaryUseCase getPortfolioSummaryUseCase = Mockito.mock(GetPortfolioSummaryUseCase.class);
    private final PositionRestMapper mapper = Mockito.mock(PositionRestMapper.class);
    private final UserContext userContext = Mockito.mock(UserContext.class);

    private PortfolioController controller;

    @BeforeEach
    void setUp() {
        controller = new PortfolioController(getPortfolioSummaryUseCase, mapper, userContext);
        when(userContext.requireUserId()).thenReturn(USER_ID);
    }

    @Test
    void givenSummary_whenSummary_thenReturns200WithMappedBody() {
        PortfolioSummary summary = sampleSummary();
        PortfolioSummaryResponse responseBody = sampleSummaryResponse(summary);

        when(getPortfolioSummaryUseCase.execute(GetPortfolioSummaryUseCase.Query.all(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioSummaryUseCase.Result.Success(summary)));
        when(mapper.toResponse(summary)).thenReturn(responseBody);

        Response response = controller.summary().await().indefinitely();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(responseBody, response.getEntity());
    }

    @Test
    void givenSummary_whenSummaryActive_thenReturns200WithMappedBody() {
        PortfolioSummary summary = sampleSummary();
        PortfolioSummaryResponse responseBody = sampleSummaryResponse(summary);

        when(getPortfolioSummaryUseCase.execute(GetPortfolioSummaryUseCase.Query.active(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioSummaryUseCase.Result.Success(summary)));
        when(mapper.toResponse(summary)).thenReturn(responseBody);

        Response response = controller.summaryActive().await().indefinitely();

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(responseBody, response.getEntity());
    }

    private static PortfolioSummary sampleSummary() {
        return PortfolioSummary.of(
                Currency.USD,
                new BigDecimal("5000"),
                new BigDecimal("4000"),
                10,
                8);
    }

    private static PortfolioSummaryResponse sampleSummaryResponse(PortfolioSummary summary) {
        return new PortfolioSummaryResponse(
                summary.valuationCurrency(),
                summary.totalMarketValue(),
                summary.totalCost(),
                summary.totalUnrealizedGainLoss(),
                summary.totalUnrealizedGainLossPercentage(),
                summary.totalPositions(),
                summary.activePositions());
    }
}

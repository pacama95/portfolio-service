package com.portfolio.adapters.incoming.mcp;

import com.portfolio.adapters.incoming.mcp.dto.CapitalFlowEntryDto;
import com.portfolio.adapters.incoming.mcp.dto.CapitalFlowsDto;
import com.portfolio.adapters.incoming.mcp.dto.DailyPositionHistoryDto;
import com.portfolio.adapters.incoming.mcp.dto.DailyPositionSnapshotDto;
import com.portfolio.adapters.incoming.mcp.dto.DailyValuationDto;
import com.portfolio.adapters.incoming.mcp.dto.PerformanceInputsDto;
import com.portfolio.adapters.incoming.mcp.dto.PriceHistoryDto;
import com.portfolio.adapters.incoming.mcp.dto.PriceHistoryEntryDto;
import com.portfolio.adapters.incoming.mcp.dto.ValuationHistoryDto;
import com.portfolio.adapters.incoming.mcp.mapper.HistoryMcpMapper;
import com.portfolio.core.model.CapitalFlowEntry;
import com.portfolio.core.model.CapitalFlowKind;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyPositionSnapshot;
import com.portfolio.core.model.DailyValuation;
import com.portfolio.core.model.PerformanceInputs;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetCapitalFlowsUseCase;
import com.portfolio.core.ports.incoming.GetDailyPositionHistoryUseCase;
import com.portfolio.core.ports.incoming.GetPerformanceInputsUseCase;
import com.portfolio.core.ports.incoming.GetPortfolioValuationHistoryUseCase;
import com.portfolio.core.ports.incoming.GetPriceHistoryUseCase;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class DailyHistoryMcpToolsTest {

    private static final UserId USER_ID = UserId.of("user-1");
    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 31);

    private final GetDailyPositionHistoryUseCase dailyPositionHistoryUseCase = Mockito.mock(GetDailyPositionHistoryUseCase.class);
    private final GetPriceHistoryUseCase priceHistoryUseCase = Mockito.mock(GetPriceHistoryUseCase.class);
    private final GetPortfolioValuationHistoryUseCase valuationHistoryUseCase = Mockito.mock(GetPortfolioValuationHistoryUseCase.class);
    private final GetCapitalFlowsUseCase capitalFlowsUseCase = Mockito.mock(GetCapitalFlowsUseCase.class);
    private final GetPerformanceInputsUseCase performanceInputsUseCase = Mockito.mock(GetPerformanceInputsUseCase.class);
    private final HistoryMcpMapper mapper = Mockito.mock(HistoryMcpMapper.class);
    private final McpUserIdentity identity = Mockito.mock(McpUserIdentity.class);
    private final McpFailureTranslator failureTranslator = Mockito.mock(McpFailureTranslator.class);

    private DailyHistoryMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new DailyHistoryMcpTools(
                dailyPositionHistoryUseCase, priceHistoryUseCase, valuationHistoryUseCase,
                capitalFlowsUseCase, performanceInputsUseCase, mapper, identity, failureTranslator);
        when(identity.requireUserId()).thenReturn(USER_ID);
        when(failureTranslator.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void givenSnapshots_whenGetDailyPositionHistory_thenReturnsMappedList() {
        DailyPositionSnapshot snapshot = new DailyPositionSnapshot("AAPL", FROM, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, Currency.USD);
        DailyPositionSnapshotDto dto = new DailyPositionSnapshotDto("AAPL", FROM, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, Currency.USD);
        when(dailyPositionHistoryUseCase.execute(new GetDailyPositionHistoryUseCase.Query(USER_ID, FROM, TO, "AAPL")))
                .thenReturn(Uni.createFrom().item(new GetDailyPositionHistoryUseCase.Result.Success(List.of(snapshot))));
        when(mapper.toSnapshotDtos(List.of(snapshot))).thenReturn(List.of(dto));

        DailyPositionHistoryDto result = tools.getDailyPositionHistory(FROM, TO, "AAPL")
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(List.of(dto), result.snapshots());
    }

    @Test
    void givenInvalidRange_whenGetDailyPositionHistory_thenFailsWithInvalidRequestCode() {
        when(dailyPositionHistoryUseCase.execute(new GetDailyPositionHistoryUseCase.Query(USER_ID, FROM, TO, null)))
                .thenReturn(Uni.createFrom().item(new GetDailyPositionHistoryUseCase.Result.InvalidRequest("range too wide")));

        Throwable failure = tools.getDailyPositionHistory(FROM, TO, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("INVALID_REQUEST: range too wide", failure.getMessage());
    }

    @Test
    void givenConflict_whenGetDailyPositionHistory_thenFailsWithConflictCode() {
        when(dailyPositionHistoryUseCase.execute(new GetDailyPositionHistoryUseCase.Query(USER_ID, FROM, TO, null)))
                .thenReturn(Uni.createFrom().item(new GetDailyPositionHistoryUseCase.Result.Conflict("bad state")));

        Throwable failure = tools.getDailyPositionHistory(FROM, TO, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("CONFLICT: bad state", failure.getMessage());
    }

    @Test
    void givenSymbols_whenGetPriceHistory_thenReturnsMappedList() {
        OffsetDateTime fetchedAt = OffsetDateTime.now();
        PriceHistoryEntry entry = new PriceHistoryEntry("AAPL", FROM, BigDecimal.TEN, BigDecimal.ONE, Currency.USD, fetchedAt);
        PriceHistoryEntryDto dto = new PriceHistoryEntryDto("AAPL", FROM, BigDecimal.TEN, BigDecimal.ONE, Currency.USD, fetchedAt);
        when(priceHistoryUseCase.execute(new GetPriceHistoryUseCase.Query(USER_ID, List.of("AAPL"), FROM, TO)))
                .thenReturn(Uni.createFrom().item(new GetPriceHistoryUseCase.Result.Success(List.of(entry))));
        when(mapper.toPriceHistoryDtos(List.of(entry))).thenReturn(List.of(dto));

        PriceHistoryDto result = tools.getPriceHistory(List.of("AAPL"), FROM, TO)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(List.of(dto), result.entries());
    }

    @Test
    void givenInvalidSymbols_whenGetPriceHistory_thenFailsWithInvalidRequestCode() {
        when(priceHistoryUseCase.execute(new GetPriceHistoryUseCase.Query(USER_ID, List.of("ZZZZ"), null, null)))
                .thenReturn(Uni.createFrom().item(new GetPriceHistoryUseCase.Result.InvalidRequest("unknown symbol")));

        Throwable failure = tools.getPriceHistory(List.of("ZZZZ"), null, null)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("INVALID_REQUEST: unknown symbol", failure.getMessage());
    }

    @Test
    void givenValuations_whenGetPortfolioValuationHistory_thenReturnsMappedList() {
        DailyValuation valuation = new DailyValuation(FROM, Currency.USD, BigDecimal.TEN, BigDecimal.ONE, true);
        DailyValuationDto dto = new DailyValuationDto(FROM, Currency.USD, BigDecimal.TEN, BigDecimal.ONE, true);
        when(valuationHistoryUseCase.execute(new GetPortfolioValuationHistoryUseCase.Query(USER_ID, FROM, TO)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioValuationHistoryUseCase.Result.Success(List.of(valuation))));
        when(mapper.toValuationDtos(List.of(valuation))).thenReturn(List.of(dto));

        ValuationHistoryDto result = tools.getPortfolioValuationHistory(FROM, TO)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(List.of(dto), result.valuations());
    }

    @Test
    void givenInvalidRange_whenGetPortfolioValuationHistory_thenFailsWithInvalidRequestCode() {
        when(valuationHistoryUseCase.execute(new GetPortfolioValuationHistoryUseCase.Query(USER_ID, FROM, TO)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioValuationHistoryUseCase.Result.InvalidRequest("bad range")));

        Throwable failure = tools.getPortfolioValuationHistory(FROM, TO)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("INVALID_REQUEST: bad range", failure.getMessage());
    }

    @Test
    void givenConflict_whenGetPortfolioValuationHistory_thenFailsWithConflictCode() {
        when(valuationHistoryUseCase.execute(new GetPortfolioValuationHistoryUseCase.Query(USER_ID, FROM, TO)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioValuationHistoryUseCase.Result.Conflict("bad state")));

        Throwable failure = tools.getPortfolioValuationHistory(FROM, TO)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("CONFLICT: bad state", failure.getMessage());
    }

    @Test
    void givenFlows_whenGetCapitalFlows_thenReturnsMappedList() {
        CapitalFlowEntry flow = new CapitalFlowEntry(UUID.randomUUID(), "AAPL", FROM, CapitalFlowKind.BUY, Currency.USD, BigDecimal.TEN, BigDecimal.ONE);
        CapitalFlowEntryDto dto = new CapitalFlowEntryDto(flow.transactionId(), "AAPL", FROM, CapitalFlowKind.BUY, Currency.USD, BigDecimal.TEN, BigDecimal.ONE);
        when(capitalFlowsUseCase.execute(new GetCapitalFlowsUseCase.Query(USER_ID, FROM, TO)))
                .thenReturn(Uni.createFrom().item(new GetCapitalFlowsUseCase.Result.Success(List.of(flow))));
        when(mapper.toCapitalFlowDtos(List.of(flow))).thenReturn(List.of(dto));

        CapitalFlowsDto result = tools.getCapitalFlows(FROM, TO)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(List.of(dto), result.flows());
    }

    @Test
    void givenInvalidRange_whenGetCapitalFlows_thenFailsWithInvalidRequestCode() {
        when(capitalFlowsUseCase.execute(new GetCapitalFlowsUseCase.Query(USER_ID, FROM, TO)))
                .thenReturn(Uni.createFrom().item(new GetCapitalFlowsUseCase.Result.InvalidRequest("bad range")));

        Throwable failure = tools.getCapitalFlows(FROM, TO)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("INVALID_REQUEST: bad range", failure.getMessage());
    }

    @Test
    void givenInputs_whenGetPerformanceInputs_thenReturnsBundledMappedDto() {
        DailyValuation valuation = new DailyValuation(FROM, Currency.USD, BigDecimal.TEN, BigDecimal.ONE, true);
        CapitalFlowEntry flow = new CapitalFlowEntry(UUID.randomUUID(), "AAPL", FROM, CapitalFlowKind.BUY, Currency.USD, BigDecimal.TEN, BigDecimal.ONE);
        PerformanceInputs inputs = new PerformanceInputs(List.of(valuation), List.of(flow), 2);
        DailyValuationDto valuationDto = new DailyValuationDto(FROM, Currency.USD, BigDecimal.TEN, BigDecimal.ONE, true);
        CapitalFlowEntryDto flowDto = new CapitalFlowEntryDto(flow.transactionId(), "AAPL", FROM, CapitalFlowKind.BUY, Currency.USD, BigDecimal.TEN, BigDecimal.ONE);
        when(performanceInputsUseCase.execute(new GetPerformanceInputsUseCase.Query(USER_ID, FROM, TO)))
                .thenReturn(Uni.createFrom().item(new GetPerformanceInputsUseCase.Result.Success(inputs)));
        when(mapper.toValuationDtos(List.of(valuation))).thenReturn(List.of(valuationDto));
        when(mapper.toCapitalFlowDtos(List.of(flow))).thenReturn(List.of(flowDto));

        PerformanceInputsDto result = tools.getPerformanceInputs(FROM, TO)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(List.of(valuationDto), result.valuations());
        assertEquals(List.of(flowDto), result.capitalFlows());
        assertEquals(2, result.incompleteDays());
    }

    @Test
    void givenInvalidRange_whenGetPerformanceInputs_thenFailsWithInvalidRequestCode() {
        when(performanceInputsUseCase.execute(new GetPerformanceInputsUseCase.Query(USER_ID, FROM, TO)))
                .thenReturn(Uni.createFrom().item(new GetPerformanceInputsUseCase.Result.InvalidRequest("bad range")));

        Throwable failure = tools.getPerformanceInputs(FROM, TO)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("INVALID_REQUEST: bad range", failure.getMessage());
    }

    @Test
    void givenConflict_whenGetPerformanceInputs_thenFailsWithConflictCode() {
        when(performanceInputsUseCase.execute(new GetPerformanceInputsUseCase.Query(USER_ID, FROM, TO)))
                .thenReturn(Uni.createFrom().item(new GetPerformanceInputsUseCase.Result.Conflict("bad state")));

        Throwable failure = tools.getPerformanceInputs(FROM, TO)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("CONFLICT: bad state", failure.getMessage());
    }
}

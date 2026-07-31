package com.portfolio.adapters.incoming.mcp;

import com.portfolio.adapters.incoming.mcp.dto.PortfolioSummaryDto;
import com.portfolio.adapters.incoming.mcp.mapper.PositionMcpMapper;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.PortfolioSummary;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPortfolioSummaryUseCase;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PortfolioMcpToolsTest {

    private static final UserId USER_ID = UserId.of("user-1");

    private final GetPortfolioSummaryUseCase getPortfolioSummaryUseCase = Mockito.mock(GetPortfolioSummaryUseCase.class);
    private final PositionMcpMapper mapper = Mockito.mock(PositionMcpMapper.class);
    private final McpUserIdentity identity = Mockito.mock(McpUserIdentity.class);
    private final McpFailureTranslator failureTranslator = Mockito.mock(McpFailureTranslator.class);

    private PortfolioMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new PortfolioMcpTools(getPortfolioSummaryUseCase, mapper, identity, failureTranslator);
        when(identity.requireUserId()).thenReturn(USER_ID);
        when(failureTranslator.sanitize(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void givenActiveOnlyFalse_whenGetPortfolioSummary_thenQueriesAll() {
        PortfolioSummary summary = PortfolioSummary.of(Currency.USD, BigDecimal.TEN, BigDecimal.ONE, 5, 3);
        PortfolioSummaryDto dto = new PortfolioSummaryDto(Currency.USD, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 5, 3, true);
        when(getPortfolioSummaryUseCase.execute(GetPortfolioSummaryUseCase.Query.all(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioSummaryUseCase.Result.Success(summary)));
        when(mapper.toDto(summary)).thenReturn(dto);

        PortfolioSummaryDto result = tools.getPortfolioSummary(false)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(dto, result);
    }

    @Test
    void givenActiveOnlyTrue_whenGetPortfolioSummary_thenQueriesActiveOnly() {
        PortfolioSummary summary = PortfolioSummary.of(Currency.USD, BigDecimal.TEN, BigDecimal.ONE, 5, 3);
        PortfolioSummaryDto dto = new PortfolioSummaryDto(Currency.USD, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 5, 3, true);
        when(getPortfolioSummaryUseCase.execute(GetPortfolioSummaryUseCase.Query.active(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioSummaryUseCase.Result.Success(summary)));
        when(mapper.toDto(summary)).thenReturn(dto);

        PortfolioSummaryDto result = tools.getPortfolioSummary(true)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertEquals(dto, result);
    }

    @Test
    void givenConflict_whenGetPortfolioSummary_thenFailsWithConflictCode() {
        when(getPortfolioSummaryUseCase.execute(GetPortfolioSummaryUseCase.Query.all(USER_ID)))
                .thenReturn(Uni.createFrom().item(new GetPortfolioSummaryUseCase.Result.Conflict("bad state")));

        Throwable failure = tools.getPortfolioSummary(false)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .getFailure();

        assertEquals("CONFLICT: bad state", failure.getMessage());
    }
}

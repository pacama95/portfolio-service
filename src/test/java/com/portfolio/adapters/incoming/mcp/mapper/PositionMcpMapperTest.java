package com.portfolio.adapters.incoming.mcp.mapper;

import com.portfolio.adapters.incoming.mcp.dto.PortfolioSummaryDto;
import com.portfolio.adapters.incoming.mcp.dto.PositionDto;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.PortfolioSummary;
import com.portfolio.core.model.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionMcpMapperTest {

    private PositionMcpMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new PositionMcpMapperImpl();
    }

    @Test
    void givenNullPosition_whenToDto_thenReturnsNull() {
        assertNull(mapper.toDto((Position) null));
    }

    @Test
    void givenNullSummary_whenToDto_thenReturnsNull() {
        assertNull(mapper.toDto((PortfolioSummary) null));
    }

    @Test
    void givenPositionWithMarketPrice_whenToDto_thenMapsComputedValues() {
        Position position = new Position(
                "AAPL",
                "Apple Inc.",
                new BigDecimal("10"),
                new BigDecimal("100"),
                new BigDecimal("1000"),
                new BigDecimal("5"),
                Currency.USD,
                new BigDecimal("150"),
                LocalDate.of(2024, 1, 1),
                true,
                "NASDAQ",
                "US");

        PositionDto dto = mapper.toDto(position);

        assertEquals("AAPL", dto.ticker());
        assertEquals("Apple Inc.", dto.companyName());
        assertEquals(new BigDecimal("10"), dto.totalQuantity());
        assertEquals(new BigDecimal("100"), dto.averagePrice());
        assertEquals(new BigDecimal("150"), dto.currentPrice());
        assertEquals(new BigDecimal("1000"), dto.totalCost());
        assertEquals(Currency.USD, dto.currency());
        assertEquals(LocalDate.of(2024, 1, 1), dto.firstPurchaseDate());
        assertTrue(dto.isActive());
        assertEquals(new BigDecimal("1500.000000"), dto.marketValue());
        assertEquals(new BigDecimal("500.000000"), dto.unrealizedGainLoss());
        assertEquals(new BigDecimal("50.000000"), dto.unrealizedGainLossPercentage());
        assertEquals("NASDAQ", dto.exchange());
        assertEquals("US", dto.country());
    }

    @Test
    void givenPositionWithoutMarketPrice_whenToDto_thenMarketValueIsZero() {
        Position position = new Position(
                "MSFT",
                "Microsoft Corp.",
                new BigDecimal("5"),
                new BigDecimal("200"),
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                Currency.USD,
                null,
                LocalDate.of(2024, 2, 1),
                false,
                "NYSE",
                "US");

        PositionDto dto = mapper.toDto(position);

        assertNull(dto.currentPrice());
        assertEquals(BigDecimal.ZERO, dto.marketValue());
        assertEquals(new BigDecimal("-1000"), dto.unrealizedGainLoss());
    }

    @Test
    void givenPortfolioSummary_whenToDto_thenMapsAllFields() {
        PortfolioSummary summary = PortfolioSummary.of(
                Currency.USD,
                new BigDecimal("5000"),
                new BigDecimal("4000"),
                10,
                8);

        PortfolioSummaryDto dto = mapper.toDto(summary);

        assertEquals(Currency.USD, dto.valuationCurrency());
        assertEquals(new BigDecimal("5000"), dto.totalMarketValue());
        assertEquals(new BigDecimal("4000"), dto.totalCost());
        assertEquals(new BigDecimal("1000"), dto.totalUnrealizedGainLoss());
        assertEquals(new BigDecimal("25.000000"), dto.totalUnrealizedGainLossPercentage());
        assertEquals(10, dto.totalPositions());
        assertEquals(8, dto.activePositions());
        assertTrue(dto.complete());
    }
}

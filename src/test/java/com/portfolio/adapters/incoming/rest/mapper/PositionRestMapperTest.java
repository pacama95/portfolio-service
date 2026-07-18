package com.portfolio.adapters.incoming.rest.mapper;

import com.portfolio.adapters.incoming.rest.dto.PortfolioSummaryResponse;
import com.portfolio.adapters.incoming.rest.dto.PositionResponse;
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

class PositionRestMapperTest {

    private PositionRestMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new PositionRestMapperImpl();
    }

    @Test
    void givenNullPosition_whenToResponse_thenReturnsNull() {
        assertNull(mapper.toResponse((Position) null));
    }

    @Test
    void givenNullSummary_whenToResponse_thenReturnsNull() {
        assertNull(mapper.toResponse((PortfolioSummary) null));
    }

    @Test
    void givenPositionWithMarketPrice_whenToResponse_thenMapsComputedValues() {
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

        PositionResponse response = mapper.toResponse(position);

        assertEquals("AAPL", response.ticker());
        assertEquals("Apple Inc.", response.companyName());
        assertEquals(new BigDecimal("10"), response.totalQuantity());
        assertEquals(new BigDecimal("100"), response.averagePrice());
        assertEquals(new BigDecimal("150"), response.currentPrice());
        assertEquals(new BigDecimal("1000"), response.totalCost());
        assertEquals(Currency.USD, response.currency());
        assertEquals(LocalDate.of(2024, 1, 1), response.firstPurchaseDate());
        assertTrue(response.isActive());
        assertEquals(new BigDecimal("1500.000000"), response.marketValue());
        assertEquals(new BigDecimal("500.000000"), response.unrealizedGainLoss());
        assertEquals(new BigDecimal("50.000000"), response.unrealizedGainLossPercentage());
        assertEquals("NASDAQ", response.exchange());
        assertEquals("US", response.country());
    }

    @Test
    void givenPositionWithoutMarketPrice_whenToResponse_thenMarketValueIsZero() {
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

        PositionResponse response = mapper.toResponse(position);

        assertNull(response.currentPrice());
        assertEquals(BigDecimal.ZERO, response.marketValue());
        assertEquals(new BigDecimal("-1000"), response.unrealizedGainLoss());
        assertEquals(new BigDecimal("-100.000000"), response.unrealizedGainLossPercentage());
    }

    @Test
    void givenZeroInvestedAmount_whenToResponse_thenUnrealizedGainLossPercentageIsZero() {
        Position position = new Position(
                "TSLA",
                "Tesla Inc.",
                new BigDecimal("3"),
                new BigDecimal("250"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Currency.USD,
                new BigDecimal("300"),
                LocalDate.of(2024, 3, 1),
                true,
                "NASDAQ",
                "US");

        PositionResponse response = mapper.toResponse(position);

        assertEquals(BigDecimal.ZERO, response.unrealizedGainLossPercentage());
        assertEquals(new BigDecimal("900.000000"), response.marketValue());
        assertEquals(new BigDecimal("900.000000"), response.unrealizedGainLoss());
    }

    @Test
    void givenPortfolioSummary_whenToResponse_thenMapsAllFields() {
        PortfolioSummary summary = PortfolioSummary.of(
                Currency.USD,
                new BigDecimal("5000"),
                new BigDecimal("4000"),
                10,
                8);

        PortfolioSummaryResponse response = mapper.toResponse(summary);

        assertEquals(Currency.USD, response.valuationCurrency());
        assertEquals(new BigDecimal("5000"), response.totalMarketValue());
        assertEquals(new BigDecimal("4000"), response.totalCost());
        assertEquals(new BigDecimal("1000"), response.totalUnrealizedGainLoss());
        assertEquals(new BigDecimal("25.000000"), response.totalUnrealizedGainLossPercentage());
        assertEquals(10, response.totalPositions());
        assertEquals(8, response.activePositions());
    }
}

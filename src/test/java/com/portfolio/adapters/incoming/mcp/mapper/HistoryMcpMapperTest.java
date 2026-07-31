package com.portfolio.adapters.incoming.mcp.mapper;

import com.portfolio.adapters.incoming.mcp.dto.CapitalFlowEntryDto;
import com.portfolio.adapters.incoming.mcp.dto.DailyPositionSnapshotDto;
import com.portfolio.adapters.incoming.mcp.dto.DailyValuationDto;
import com.portfolio.adapters.incoming.mcp.dto.IngestionRunDto;
import com.portfolio.adapters.incoming.mcp.dto.PriceHistoryEntryDto;
import com.portfolio.core.model.CapitalFlowEntry;
import com.portfolio.core.model.CapitalFlowKind;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyPositionSnapshot;
import com.portfolio.core.model.DailyValuation;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.ports.incoming.GetPriceIngestionRunUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HistoryMcpMapperTest {

    private HistoryMcpMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new HistoryMcpMapperImpl();
    }

    @Test
    void givenNullSnapshot_whenToDto_thenReturnsNull() {
        assertNull(mapper.toDto((DailyPositionSnapshot) null));
    }

    @Test
    void givenNullSnapshotList_whenToSnapshotDtos_thenReturnsNull() {
        assertNull(mapper.toSnapshotDtos(null));
    }

    @Test
    void givenNullPriceHistoryEntry_whenToDto_thenReturnsNull() {
        assertNull(mapper.toDto((PriceHistoryEntry) null));
    }

    @Test
    void givenPriceHistoryEntryList_whenToPriceHistoryDtos_thenMapsEachElement() {
        PriceHistoryEntry entry = new PriceHistoryEntry(
                "AAPL", LocalDate.of(2024, 1, 1), new BigDecimal("150"), new BigDecimal("149"), Currency.USD,
                OffsetDateTime.parse("2024-01-15T10:00:00Z"));

        List<PriceHistoryEntryDto> dtos = mapper.toPriceHistoryDtos(List.of(entry));

        assertEquals(1, dtos.size());
        assertEquals("AAPL", dtos.get(0).symbol());
    }

    @Test
    void givenNullPriceHistoryEntryList_whenToPriceHistoryDtos_thenReturnsNull() {
        assertNull(mapper.toPriceHistoryDtos(null));
    }

    @Test
    void givenNullDailyValuation_whenToDto_thenReturnsNull() {
        assertNull(mapper.toDto((DailyValuation) null));
    }

    @Test
    void givenDailyValuationList_whenToValuationDtos_thenMapsEachElement() {
        DailyValuation valuation = new DailyValuation(
                LocalDate.of(2024, 1, 1), Currency.USD, new BigDecimal("1000"), new BigDecimal("900"), true);

        List<DailyValuationDto> dtos = mapper.toValuationDtos(List.of(valuation));

        assertEquals(1, dtos.size());
        assertEquals(Currency.USD, dtos.get(0).valuationCurrency());
    }

    @Test
    void givenNullDailyValuationList_whenToValuationDtos_thenReturnsNull() {
        assertNull(mapper.toValuationDtos(null));
    }

    @Test
    void givenNullCapitalFlowEntry_whenToDto_thenReturnsNull() {
        assertNull(mapper.toDto((CapitalFlowEntry) null));
    }

    @Test
    void givenCapitalFlowEntryList_whenToCapitalFlowDtos_thenMapsEachElement() {
        CapitalFlowEntry entry = new CapitalFlowEntry(
                UUID.randomUUID(), "AAPL", LocalDate.of(2024, 1, 1), CapitalFlowKind.BUY, Currency.USD,
                new BigDecimal("1000"), new BigDecimal("1"));

        List<CapitalFlowEntryDto> dtos = mapper.toCapitalFlowDtos(List.of(entry));

        assertEquals(1, dtos.size());
        assertEquals("AAPL", dtos.get(0).ticker());
    }

    @Test
    void givenNullCapitalFlowEntryList_whenToCapitalFlowDtos_thenReturnsNull() {
        assertNull(mapper.toCapitalFlowDtos(null));
    }

    @Test
    void givenSnapshot_whenToDto_thenMapsAllFields() {
        DailyPositionSnapshot snapshot = new DailyPositionSnapshot(
                "AAPL", LocalDate.of(2024, 1, 1), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, Currency.USD);

        DailyPositionSnapshotDto dto = mapper.toDto(snapshot);

        assertEquals("AAPL", dto.ticker());
        assertEquals(LocalDate.of(2024, 1, 1), dto.snapshotDate());
        assertEquals(BigDecimal.TEN, dto.sharesOwned());
        assertEquals(BigDecimal.ONE, dto.averageCostPerShare());
        assertEquals(BigDecimal.TEN, dto.totalInvestedAmount());
        assertEquals(Currency.USD, dto.currency());
    }

    @Test
    void givenSnapshotList_whenToSnapshotDtos_thenMapsEachElement() {
        DailyPositionSnapshot snapshot = new DailyPositionSnapshot(
                "AAPL", LocalDate.of(2024, 1, 1), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, Currency.USD);

        List<DailyPositionSnapshotDto> dtos = mapper.toSnapshotDtos(List.of(snapshot));

        assertEquals(1, dtos.size());
        assertEquals("AAPL", dtos.get(0).ticker());
    }

    @Test
    void givenPriceHistoryEntry_whenToDto_thenMapsAllFields() {
        OffsetDateTime fetchedAt = OffsetDateTime.parse("2024-01-15T10:00:00Z");
        PriceHistoryEntry entry = new PriceHistoryEntry(
                "AAPL", LocalDate.of(2024, 1, 1), new BigDecimal("150"), new BigDecimal("149"), Currency.USD, fetchedAt);

        PriceHistoryEntryDto dto = mapper.toDto(entry);

        assertEquals("AAPL", dto.symbol());
        assertEquals(LocalDate.of(2024, 1, 1), dto.priceDate());
        assertEquals(new BigDecimal("150"), dto.closePrice());
        assertEquals(new BigDecimal("149"), dto.adjustedClosePrice());
        assertEquals(Currency.USD, dto.currency());
        assertEquals(fetchedAt, dto.fetchedAt());
    }

    @Test
    void givenDailyValuation_whenToDto_thenMapsAllFields() {
        DailyValuation valuation = new DailyValuation(
                LocalDate.of(2024, 1, 1), Currency.USD, new BigDecimal("1000"), new BigDecimal("900"), true);

        DailyValuationDto dto = mapper.toDto(valuation);

        assertEquals(LocalDate.of(2024, 1, 1), dto.date());
        assertEquals(Currency.USD, dto.valuationCurrency());
        assertEquals(new BigDecimal("1000"), dto.totalMarketValue());
        assertEquals(new BigDecimal("900"), dto.totalCost());
        assertEquals(true, dto.complete());
    }

    @Test
    void givenCapitalFlowEntry_whenToDto_thenMapsAllFields() {
        UUID transactionId = UUID.randomUUID();
        CapitalFlowEntry entry = new CapitalFlowEntry(
                transactionId, "AAPL", LocalDate.of(2024, 1, 1), CapitalFlowKind.BUY, Currency.USD,
                new BigDecimal("1000"), new BigDecimal("1"));

        CapitalFlowEntryDto dto = mapper.toDto(entry);

        assertEquals(transactionId, dto.transactionId());
        assertEquals("AAPL", dto.ticker());
        assertEquals(LocalDate.of(2024, 1, 1), dto.flowDate());
        assertEquals(CapitalFlowKind.BUY, dto.kind());
        assertEquals(Currency.USD, dto.currency());
        assertEquals(new BigDecimal("1000"), dto.amount());
        assertEquals(new BigDecimal("1"), dto.fees());
    }

    @Test
    void givenNullIngestionRunStatus_whenToDto_thenReturnsNull() {
        assertNull(mapper.toDto((GetPriceIngestionRunUseCase.RunStatus) null));
    }

    @Test
    void givenIngestionRunStatus_whenToDto_thenMapsAllFields() {
        UUID id = UUID.randomUUID();
        OffsetDateTime startedAt = OffsetDateTime.parse("2024-01-15T10:00:00Z");
        OffsetDateTime completedAt = OffsetDateTime.parse("2024-01-15T10:05:00Z");
        GetPriceIngestionRunUseCase.RunStatus status = new GetPriceIngestionRunUseCase.RunStatus(
                id, "COMPLETED", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31), "AAPL",
                10, 10, null, startedAt, completedAt);

        IngestionRunDto dto = mapper.toDto(status);

        assertEquals(id, dto.id());
        assertEquals("COMPLETED", dto.status());
        assertEquals(LocalDate.of(2024, 1, 1), dto.fromDate());
        assertEquals(LocalDate.of(2024, 1, 31), dto.toDate());
        assertEquals("AAPL", dto.ticker());
        assertEquals(10, dto.symbolsRequested());
        assertEquals(10, dto.symbolsCompleted());
        assertNull(dto.errorMessage());
        assertEquals(startedAt, dto.startedAt());
        assertEquals(completedAt, dto.completedAt());
    }
}

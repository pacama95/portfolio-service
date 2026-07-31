package com.portfolio.adapters.incoming.mcp.mapper;

import com.portfolio.adapters.incoming.mcp.dto.CapitalFlowEntryDto;
import com.portfolio.adapters.incoming.mcp.dto.DailyPositionSnapshotDto;
import com.portfolio.adapters.incoming.mcp.dto.DailyValuationDto;
import com.portfolio.adapters.incoming.mcp.dto.IngestionRunDto;
import com.portfolio.adapters.incoming.mcp.dto.PriceHistoryEntryDto;
import com.portfolio.core.model.CapitalFlowEntry;
import com.portfolio.core.model.DailyPositionSnapshot;
import com.portfolio.core.model.DailyValuation;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.ports.incoming.GetPriceIngestionRunUseCase;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface HistoryMcpMapper {

    DailyPositionSnapshotDto toDto(DailyPositionSnapshot snapshot);

    List<DailyPositionSnapshotDto> toSnapshotDtos(List<DailyPositionSnapshot> snapshots);

    PriceHistoryEntryDto toDto(PriceHistoryEntry entry);

    List<PriceHistoryEntryDto> toPriceHistoryDtos(List<PriceHistoryEntry> entries);

    DailyValuationDto toDto(DailyValuation valuation);

    List<DailyValuationDto> toValuationDtos(List<DailyValuation> valuations);

    CapitalFlowEntryDto toDto(CapitalFlowEntry entry);

    List<CapitalFlowEntryDto> toCapitalFlowDtos(List<CapitalFlowEntry> entries);

    IngestionRunDto toDto(GetPriceIngestionRunUseCase.RunStatus status);
}

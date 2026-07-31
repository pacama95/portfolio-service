package com.portfolio.adapters.incoming.mcp.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record PerformanceInputsDto(
        List<DailyValuationDto> valuations,
        List<CapitalFlowEntryDto> capitalFlows,
        int incompleteDays
) {
}

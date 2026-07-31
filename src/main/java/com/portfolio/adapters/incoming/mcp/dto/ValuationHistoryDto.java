package com.portfolio.adapters.incoming.mcp.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record ValuationHistoryDto(List<DailyValuationDto> valuations) {
}

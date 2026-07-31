package com.portfolio.adapters.incoming.mcp.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record PositionListDto(List<PositionDto> positions, int count) {

    public static PositionListDto of(List<PositionDto> positions) {
        return new PositionListDto(positions, positions.size());
    }
}

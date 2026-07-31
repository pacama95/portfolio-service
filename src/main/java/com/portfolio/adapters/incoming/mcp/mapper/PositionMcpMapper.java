package com.portfolio.adapters.incoming.mcp.mapper;

import com.portfolio.adapters.incoming.mcp.dto.PortfolioSummaryDto;
import com.portfolio.adapters.incoming.mcp.dto.PositionDto;
import com.portfolio.core.model.PortfolioSummary;
import com.portfolio.core.model.Position;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PositionMcpMapper {

    @Mapping(target = "totalQuantity", source = "sharesOwned")
    @Mapping(target = "averagePrice", source = "averageCostPerShare")
    @Mapping(target = "currentPrice", source = "latestMarketPrice")
    @Mapping(target = "totalCost", source = "totalInvestedAmount")
    @Mapping(target = "isActive", source = "active")
    @Mapping(target = "marketValue", expression = "java(position.marketValue())")
    @Mapping(target = "unrealizedGainLoss", expression = "java(position.unrealizedGainLoss())")
    @Mapping(target = "unrealizedGainLossPercentage", expression = "java(position.unrealizedGainLossPercentage())")
    PositionDto toDto(Position position);

    PortfolioSummaryDto toDto(PortfolioSummary summary);
}

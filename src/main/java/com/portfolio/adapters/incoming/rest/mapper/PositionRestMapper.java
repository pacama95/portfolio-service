package com.portfolio.adapters.incoming.rest.mapper;

import com.portfolio.adapters.incoming.rest.dto.PositionResponse;
import com.portfolio.adapters.incoming.rest.dto.PortfolioSummaryResponse;
import com.portfolio.core.model.PortfolioSummary;
import com.portfolio.core.model.Position;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "cdi")
public interface PositionRestMapper {

    @Mapping(target = "totalQuantity", source = "sharesOwned")
    @Mapping(target = "averagePrice", source = "averageCostPerShare")
    @Mapping(target = "currentPrice", source = "latestMarketPrice")
    @Mapping(target = "totalCost", source = "totalInvestedAmount")
    @Mapping(target = "isActive", source = "active")
    @Mapping(target = "marketValue", expression = "java(position.marketValue())")
    @Mapping(target = "unrealizedGainLoss", expression = "java(position.unrealizedGainLoss())")
    @Mapping(target = "unrealizedGainLossPercentage", expression = "java(position.unrealizedGainLossPercentage())")
    PositionResponse toResponse(Position position);

    PortfolioSummaryResponse toResponse(PortfolioSummary summary);
}

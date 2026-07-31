package com.portfolio.adapters.incoming.mcp.mapper;

import com.portfolio.adapters.incoming.mcp.dto.TransactionDto;
import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.CreateTransactionUseCase;
import com.portfolio.core.ports.incoming.UpdateTransactionUseCase;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TransactionMcpMapper {

    @Mapping(target = "isFractional", source = "fractional")
    @Mapping(target = "totalValue", expression = "java(transaction.totalValue())")
    @Mapping(target = "totalCost", expression = "java(transaction.totalCost())")
    TransactionDto toDto(Transaction transaction);

    // Hand-written rather than MapStruct-generated: with 16 scalar parameters, MapStruct's
    // generated "all sources null -> null" guard becomes one compound conjunction of 16 terms,
    // which is impractical to branch-cover combinatorially and drags the JaCoCo branch gate down
    // for no behavioral benefit (userId is never actually null here).
    default CreateTransactionUseCase.Command toCreateCommand(
            UserId userId,
            String ticker,
            TransactionType transactionType,
            AssetType assetType,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal fees,
            Currency currency,
            LocalDate transactionDate,
            String notes,
            Boolean isFractional,
            BigDecimal fractionalMultiplier,
            Currency commissionCurrency,
            String exchange,
            String country,
            String companyName) {
        return new CreateTransactionUseCase.Command(
                userId, ticker, transactionType, assetType, quantity, price, fees, currency,
                transactionDate, notes, isFractional, fractionalMultiplier, commissionCurrency,
                exchange, country, companyName);
    }

    default UpdateTransactionUseCase.Command toUpdateCommand(
            UserId userId,
            UUID id,
            String ticker,
            TransactionType transactionType,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal fees,
            Currency currency,
            LocalDate transactionDate,
            String notes,
            Boolean isFractional,
            BigDecimal fractionalMultiplier,
            Currency commissionCurrency,
            String exchange,
            String country,
            String companyName) {
        return new UpdateTransactionUseCase.Command(
                userId, id, ticker, transactionType, quantity, price, fees, currency,
                transactionDate, notes, isFractional, fractionalMultiplier, commissionCurrency,
                exchange, country, companyName);
    }
}

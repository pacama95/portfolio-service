package com.portfolio.adapters.incoming.rest.mapper;

import com.portfolio.adapters.incoming.rest.dto.CreateTransactionRequest;
import com.portfolio.adapters.incoming.rest.dto.TransactionResponse;
import com.portfolio.adapters.incoming.rest.dto.UpdateTransactionRequest;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.CreateTransactionUseCase;
import com.portfolio.core.ports.incoming.UpdateTransactionUseCase;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

@Mapper(componentModel = "cdi")
public interface TransactionRestMapper {

    @Mapping(target = "isFractional", source = "fractional")
    @Mapping(target = "totalValue", expression = "java(transaction.totalValue())")
    @Mapping(target = "totalCost", expression = "java(transaction.totalCost())")
    TransactionResponse toResponse(Transaction transaction);

    default CreateTransactionUseCase.Command toCreateCommand(UserId userId, CreateTransactionRequest request) {
        return new CreateTransactionUseCase.Command(
                userId,
                request.ticker(),
                request.transactionType(),
                request.assetType(),
                request.quantity(),
                request.price(),
                request.fees(),
                request.currency(),
                request.transactionDate(),
                request.notes(),
                request.isFractional(),
                request.fractionalMultiplier(),
                request.commissionCurrency(),
                request.exchange(),
                request.country(),
                request.companyName());
    }

    default UpdateTransactionUseCase.Command toUpdateCommand(
            UserId userId, UUID id, UpdateTransactionRequest request) {
        return new UpdateTransactionUseCase.Command(
                userId,
                id,
                request.ticker(),
                request.transactionType(),
                request.quantity(),
                request.price(),
                request.fees(),
                request.currency(),
                request.transactionDate(),
                request.notes(),
                request.isFractional(),
                request.fractionalMultiplier(),
                request.commissionCurrency(),
                request.exchange(),
                request.country(),
                request.companyName());
    }
}

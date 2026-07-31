package com.portfolio.adapters.incoming.mcp.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record TransactionListDto(List<TransactionDto> transactions, int count) {

    public static TransactionListDto of(List<TransactionDto> transactions) {
        return new TransactionListDto(transactions, transactions.size());
    }
}

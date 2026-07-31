package com.portfolio.adapters.incoming.mcp.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record TransactionCountDto(long count) {
}

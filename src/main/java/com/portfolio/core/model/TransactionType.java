package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum TransactionType {
    BUY,
    SELL,
    DIVIDEND,
    SPLIT
}

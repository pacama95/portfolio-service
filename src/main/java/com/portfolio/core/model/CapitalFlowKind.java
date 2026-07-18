package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum CapitalFlowKind {
    BUY,
    SELL,
    DIVIDEND
}

package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum Currency {
    USD,
    EUR,
    GBP,
    CAD,
    JPY
}

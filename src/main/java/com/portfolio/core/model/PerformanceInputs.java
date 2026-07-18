package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public record PerformanceInputs(
        List<DailyValuation> valuations,
        List<CapitalFlowEntry> capitalFlows,
        int incompleteDays
) {
}

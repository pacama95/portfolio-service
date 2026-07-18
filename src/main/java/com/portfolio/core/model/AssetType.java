package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RegisterForReflection
public enum AssetType {
    AMERICAN_DEPOSITARY_RECEIPT("American Depositary Receipt"),
    BOND("Bond"),
    BOND_FUND("Bond Fund"),
    CLOSED_END_FUND("Closed-end Fund"),
    COMMON_STOCK("Common Stock"),
    DEPOSITARY_RECEIPT("Depositary Receipt"),
    DIGITAL_CURRENCY("Digital Currency"),
    ETF("ETF"),
    EXCHANGE_TRADED_NOTE("Exchange-Traded Note"),
    GLOBAL_DEPOSITARY_RECEIPT("Global Depositary Receipt"),
    LIMITED_PARTNERSHIP("Limited Partnership"),
    MUTUAL_FUND("Mutual Fund"),
    PHYSICAL_CURRENCY("Physical Currency"),
    PREFERRED_STOCK("Preferred Stock"),
    REIT("REIT"),
    RIGHT("Right"),
    STRUCTURED_PRODUCT("Structured Product"),
    TRUST("Trust"),
    UNIT("Unit"),
    WARRANT("Warrant");

    private static final Map<String, AssetType> BY_LABEL = Arrays.stream(values())
            .collect(Collectors.toMap(AssetType::getLabel, Function.identity()));

    private final String label;

    AssetType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static AssetType fromLabel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        AssetType byLabel = BY_LABEL.get(trimmed);
        if (byLabel != null) {
            return byLabel;
        }
        try {
            return AssetType.valueOf(trimmed.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid AssetType value: " + value + ". Valid values: " + Arrays.toString(values()));
        }
    }
}

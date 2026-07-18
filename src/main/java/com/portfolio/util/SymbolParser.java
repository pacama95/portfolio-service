package com.portfolio.util;

import java.util.Arrays;
import java.util.List;

public final class SymbolParser {

    private SymbolParser() {
    }

    public static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .toList();
    }
}

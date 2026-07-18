package com.portfolio.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolParserTest {

    @Test
    void givenTrailingComma_whenParseCsv_thenIgnoresEmptySegments() {
        assertEquals(List.of("AAPL"), SymbolParser.parseCsv("AAPL,"));
    }

    @Test
    void givenNullCsv_whenParseCsv_thenReturnsEmptyList() {
        assertTrue(SymbolParser.parseCsv(null).isEmpty());
    }

    @Test
    void givenBlankCsv_whenParseCsv_thenReturnsEmptyList() {
        assertTrue(SymbolParser.parseCsv("   ").isEmpty());
    }

    @Test
    void givenMultipleSymbols_whenParseCsv_thenReturnsUppercaseTrimmedSymbols() {
        assertEquals(List.of("AAPL", "MSFT"), SymbolParser.parseCsv("aapl, msft"));
    }

    @Test
    void givenOnlyCommas_whenParseCsv_thenReturnsEmptyList() {
        assertTrue(SymbolParser.parseCsv(",,").isEmpty());
    }

    @Test
    void givenPaddedSymbol_whenParseCsv_thenReturnsTrimmedUppercaseSymbol() {
        assertEquals(List.of("AAPL"), SymbolParser.parseCsv("  AAPL  "));
    }
}

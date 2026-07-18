package com.portfolio.core.application.service;

import com.portfolio.core.model.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyResolverTest {

    @Test
    void givenPlainIsoCode_whenResolve_thenReturnsCurrencyWithMultiplierOne() {
        CurrencyResolver resolver = new CurrencyResolver(Map.of());

        Optional<CurrencyResolver.Resolution> resolution = resolver.resolve("USD");

        assertEquals(Currency.USD, resolution.orElseThrow().currency());
        assertEquals(0, BigDecimal.ONE.compareTo(resolution.orElseThrow().multiplier()));
    }

    @Test
    void givenLowercaseIsoCode_whenResolve_thenResolvesCaseInsensitively() {
        CurrencyResolver resolver = new CurrencyResolver(Map.of());

        Optional<CurrencyResolver.Resolution> resolution = resolver.resolve("usd");

        assertEquals(Currency.USD, resolution.orElseThrow().currency());
    }

    @Test
    void givenConfiguredMinorUnitAlias_whenResolve_thenReturnsConfiguredCurrencyAndMultiplier() {
        CurrencyResolver resolver = new CurrencyResolver(Map.of("GBX", "GBP:0.01"));

        Optional<CurrencyResolver.Resolution> resolution = resolver.resolve("GBX");

        assertEquals(Currency.GBP, resolution.orElseThrow().currency());
        assertEquals(0, new BigDecimal("0.01").compareTo(resolution.orElseThrow().multiplier()));
    }

    @Test
    void givenCaseSensitiveMinorUnitAlias_whenResolvingIsoCode_thenDoesNotCollideWithAlias() {
        // "GBp" (pence) and "GBP" (pounds) must resolve differently even though only case differs.
        CurrencyResolver resolver = new CurrencyResolver(Map.of("GBp", "GBP:0.01"));

        CurrencyResolver.Resolution pence = resolver.resolve("GBp").orElseThrow();
        CurrencyResolver.Resolution pounds = resolver.resolve("GBP").orElseThrow();

        assertEquals(0, new BigDecimal("0.01").compareTo(pence.multiplier()));
        assertEquals(0, BigDecimal.ONE.compareTo(pounds.multiplier()));
    }

    @Test
    void givenUnknownCode_whenResolve_thenReturnsEmpty() {
        CurrencyResolver resolver = new CurrencyResolver(Map.of());

        Optional<CurrencyResolver.Resolution> resolution = resolver.resolve("NOT_A_CURRENCY");

        assertTrue(resolution.isEmpty());
    }

    @Test
    void givenAliasConfigMissingMultiplier_whenConstruct_thenThrows() {
        assertThrows(IllegalArgumentException.class, () -> new CurrencyResolver(Map.of("GBX", "GBP")));
    }

    @Test
    void givenAliasConfigWithUnknownCurrency_whenConstruct_thenThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new CurrencyResolver(Map.of("GBX", "NOT_A_CURRENCY:0.01")));
    }

    @Test
    void givenAliasConfigWithNonNumericMultiplier_whenConstruct_thenThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new CurrencyResolver(Map.of("GBX", "GBP:not-a-number")));
    }
}

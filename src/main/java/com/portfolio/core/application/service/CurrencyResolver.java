package com.portfolio.core.application.service;

import com.portfolio.core.model.Currency;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a market-data provider's raw currency code into a domain {@link Currency} and the
 * multiplier needed to convert its quoted price into major units.
 *
 * <p>Some exchanges quote in minor units — e.g. LSE prices in pence sterling, reported by data
 * providers as "GBX" or "GBp" rather than "GBP", at 100x the major-unit price. Which codes need
 * rescaling is a market/exchange convention, not something specific to any one provider, so it
 * does not live in a provider adapter. It is driven entirely by configuration
 * ({@code application.market-data.currency-minor-units.<CODE>=<CURRENCY>:<MULTIPLIER>}) so a new
 * minor-unit quirk (any exchange, any provider) is a config entry, not a code change.
 *
 * <p>Alias lookup is exact and case-sensitive: minor-unit codes like "GBp" are only
 * distinguishable from the ISO major-unit code "GBP" by case, so configured keys are matched
 * verbatim before falling back to a case-insensitive ISO {@link Currency} lookup.
 */
@ApplicationScoped
public class CurrencyResolver {

    private static final String CONFIG_PREFIX = "application.market-data.currency-minor-units.";

    public record Resolution(Currency currency, BigDecimal multiplier) {
    }

    private final Map<String, Resolution> minorUnitAliases;

    @Inject
    public CurrencyResolver(Config config) {
        this(extractAliasConfig(config));
    }

    public CurrencyResolver(Map<String, String> aliasConfig) {
        this.minorUnitAliases = parse(aliasConfig);
    }

    public Optional<Resolution> resolve(String currencyCode) {
        Resolution alias = minorUnitAliases.get(currencyCode);
        if (alias != null) {
            return Optional.of(alias);
        }
        try {
            return Optional.of(new Resolution(Currency.valueOf(currencyCode.toUpperCase()), BigDecimal.ONE));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static Map<String, String> extractAliasConfig(Config config) {
        Map<String, String> aliasConfig = new HashMap<>();
        for (String propertyName : config.getPropertyNames()) {
            if (propertyName.startsWith(CONFIG_PREFIX)) {
                String code = propertyName.substring(CONFIG_PREFIX.length());
                config.getOptionalValue(propertyName, String.class).ifPresent(value -> aliasConfig.put(code, value));
            }
        }
        return aliasConfig;
    }

    private static Map<String, Resolution> parse(Map<String, String> aliasConfig) {
        Map<String, Resolution> parsed = new HashMap<>();
        for (Map.Entry<String, String> entry : aliasConfig.entrySet()) {
            parsed.put(entry.getKey(), parseEntry(entry.getKey(), entry.getValue()));
        }
        return parsed;
    }

    private static Resolution parseEntry(String code, String value) {
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid " + CONFIG_PREFIX + code + "='" + value + "': expected CURRENCY:MULTIPLIER");
        }
        Currency currency;
        try {
            currency = Currency.valueOf(parts[0].trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Invalid " + CONFIG_PREFIX + code + "='" + value + "': unknown currency '" + parts[0] + "'", ex);
        }
        BigDecimal multiplier;
        try {
            multiplier = new BigDecimal(parts[1].trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Invalid " + CONFIG_PREFIX + code + "='" + value + "': multiplier '" + parts[1]
                            + "' is not a number", ex);
        }
        return new Resolution(currency, multiplier);
    }
}

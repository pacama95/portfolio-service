package com.portfolio.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * Pure "as of date" lookup shared by price and FX series: the last known value on or before a
 * date, rejected if it is older than the allowed staleness.
 */
public final class TimeSeriesLookup {

    private TimeSeriesLookup() {
    }

    public static Optional<BigDecimal> floorWithinStaleness(
            NavigableMap<LocalDate, BigDecimal> series, LocalDate date, int maxStalenessDays) {
        if (series == null) {
            return Optional.empty();
        }
        Map.Entry<LocalDate, BigDecimal> floor = series.floorEntry(date);
        if (floor == null || floor.getKey().isBefore(date.minusDays(maxStalenessDays))) {
            return Optional.empty();
        }
        return Optional.of(floor.getValue());
    }
}

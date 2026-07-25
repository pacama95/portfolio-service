package com.portfolio.core.ports.outgoing;

import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.SpotQuote;
import io.smallrye.mutiny.Uni;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Application-facing market data API (read-through store + provider).
 */
public interface MarketDataPort {

    Uni<BigDecimal> getSpotPrice(String symbol);

    Uni<BigDecimal> getFxRate(Currency base, Currency quote);

    Uni<List<PriceHistoryEntry>> getPriceHistory(String symbol, LocalDate from, LocalDate to);

    Uni<List<FxRateEntry>> getFxHistory(Currency base, Currency quote, LocalDate from, LocalDate to);

    Uni<SpotQuote> setManualPrice(String symbol, BigDecimal price, Currency currency);

    Uni<Void> clearManualPrice(String symbol);
}

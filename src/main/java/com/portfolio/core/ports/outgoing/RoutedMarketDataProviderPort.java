package com.portfolio.core.ports.outgoing;

import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.MarketDataProviderResult;
import com.portfolio.core.model.PriceHistoryEntry;
import io.smallrye.mutiny.Uni;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Provider-aware companion to {@link MarketDataProviderPort}; not exposed by incoming ports. */
public interface RoutedMarketDataProviderPort {

    Uni<MarketDataProviderResult<BigDecimal>> fetchSpotPriceWithProvider(String symbol);

    Uni<MarketDataProviderResult<BigDecimal>> fetchFxRateWithProvider(Currency base, Currency quote);

    Uni<MarketDataProviderResult<List<PriceHistoryEntry>>> fetchPriceHistoryWithProvider(
            String symbol, LocalDate from, LocalDate to);

    Uni<MarketDataProviderResult<List<FxRateEntry>>> fetchFxHistoryWithProvider(
            Currency base, Currency quote, LocalDate from, LocalDate to);
}

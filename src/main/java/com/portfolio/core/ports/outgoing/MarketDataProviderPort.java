package com.portfolio.core.ports.outgoing;

import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import io.smallrye.mutiny.Uni;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface MarketDataProviderPort {

    Uni<BigDecimal> fetchSpotPrice(String symbol);

    Uni<BigDecimal> fetchFxRate(Currency base, Currency quote);

    Uni<List<PriceHistoryEntry>> fetchPriceHistory(String symbol, LocalDate from, LocalDate to);

    Uni<List<FxRateEntry>> fetchFxHistory(Currency base, Currency quote, LocalDate from, LocalDate to);
}

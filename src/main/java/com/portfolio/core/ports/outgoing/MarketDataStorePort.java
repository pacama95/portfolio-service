package com.portfolio.core.ports.outgoing;

import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.SpotQuote;
import com.portfolio.core.model.SpotQuoteKind;
import io.smallrye.mutiny.Uni;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MarketDataStorePort {

    Uni<List<PriceHistoryEntry>> findPrices(String symbol, LocalDate from, LocalDate to);

    Uni<Void> upsertPrices(List<PriceHistoryEntry> entries);

    Uni<Void> upsertPrices(List<PriceHistoryEntry> entries, String provider);

    Uni<List<FxRateEntry>> findFxRates(Currency base, Currency quote, LocalDate from, LocalDate to);

    Uni<Void> upsertFxRates(List<FxRateEntry> entries);

    Uni<Void> upsertFxRates(List<FxRateEntry> entries, String provider);

    Uni<Optional<SpotQuote>> findSpotQuote(SpotQuoteKind kind, String symbol);

    Uni<SpotQuote> upsertSpotQuote(SpotQuote quote);

    Uni<SpotQuote> upsertSpotQuote(SpotQuote quote, String provider);

    Uni<Void> deleteSpotQuote(SpotQuoteKind kind, String symbol);

    Uni<Boolean> hasCoverage(String coverageKind, String symbol, LocalDate from, LocalDate to);

    Uni<Void> recordCoverage(String coverageKind, String symbol, LocalDate from, LocalDate to, String provider);
}

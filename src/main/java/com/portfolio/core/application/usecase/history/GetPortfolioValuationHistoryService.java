package com.portfolio.core.application.usecase.history;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.domain.TimeSeriesLookup;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyPositionSnapshot;
import com.portfolio.core.model.DailyValuation;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.ports.incoming.GetPortfolioValuationHistoryUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class GetPortfolioValuationHistoryService implements GetPortfolioValuationHistoryUseCase {

    private static final Logger LOG = Logger.getLogger(GetPortfolioValuationHistoryService.class);
    private static final int PRICE_STALENESS_DAYS = 5;
    private static final int FX_STALENESS_DAYS = 5;

    private final TransactionRepository transactionRepository;
    private final MarketDataPort marketDataPort;
    private final Currency baseCurrency;

    public GetPortfolioValuationHistoryService(
            TransactionRepository transactionRepository,
            MarketDataPort marketDataPort,
            @ConfigProperty(name = "application.portfolio.base-currency", defaultValue = "USD")
            String baseCurrency) {
        this.transactionRepository = transactionRepository;
        this.marketDataPort = marketDataPort;
        this.baseCurrency = Currency.valueOf(baseCurrency);
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Getting portfolio valuation history from=%s to=%s", query.from(), query.to());
        if (query.from() == null || query.to() == null || query.from().isAfter(query.to())) {
            return Uni.createFrom().item(new Result.InvalidRequest("from/to dates are required and from <= to"));
        }
        return transactionRepository.findAll(query.userId())
                .flatMap(transactions -> buildValuations(transactions, query.from(), query.to()))
                .invoke(valuations -> LOG.infof("Portfolio valuation history days=%d", valuations.size()))
                .map(Result.Success::new);
    }

    private Uni<List<DailyValuation>> buildValuations(
            List<Transaction> transactions, LocalDate from, LocalDate to) {
        List<DailyPositionSnapshot> snapshots = LedgerReplay.dailySnapshots(
                transactions, LocalDate.MIN, to);
        Map<String, NavigableMap<LocalDate, DailyPositionSnapshot>> byTicker = snapshots.stream()
                .collect(Collectors.groupingBy(
                        DailyPositionSnapshot::ticker,
                        Collectors.toMap(
                                DailyPositionSnapshot::snapshotDate,
                                s -> s,
                                (a, b) -> b,
                                TreeMap::new)));

        List<String> tickers = new ArrayList<>(byTicker.keySet());
        if (tickers.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        Set<Currency> foreignCurrencies = snapshots.stream()
                .map(DailyPositionSnapshot::currency)
                .filter(currency -> currency != null && currency != baseCurrency)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return loadPrices(tickers, from, to)
                .flatMap(priceMap -> loadFxRates(foreignCurrencies, from, to)
                        .map(fxMap -> buildDailyValuations(byTicker, tickers, priceMap, fxMap, from, to)));
    }

    private Uni<Map<String, NavigableMap<LocalDate, BigDecimal>>> loadPrices(
            List<String> tickers, LocalDate from, LocalDate to) {
        Uni<Map<String, NavigableMap<LocalDate, BigDecimal>>> pricesUni =
                Uni.createFrom().item(new HashMap<>());
        for (String ticker : tickers) {
            pricesUni = pricesUni.flatMap(map -> marketDataPort.getPriceHistory(ticker, from, to)
                    .map(entries -> {
                        NavigableMap<LocalDate, BigDecimal> series = new TreeMap<>();
                        for (PriceHistoryEntry entry : entries) {
                            series.put(entry.priceDate(), entry.closePrice());
                        }
                        map.put(ticker, series);
                        return map;
                    }));
        }
        return pricesUni;
    }

    /**
     * FX is fetched from a week before {@code from} (rather than exactly {@code from}) so the
     * first days of the requested range still have a usable floor rate when {@code from} lands
     * on a weekend/holiday gap in the FX series.
     */
    private Uni<Map<Currency, NavigableMap<LocalDate, BigDecimal>>> loadFxRates(
            Set<Currency> foreignCurrencies, LocalDate from, LocalDate to) {
        Uni<Map<Currency, NavigableMap<LocalDate, BigDecimal>>> fxUni =
                Uni.createFrom().item(new HashMap<>());
        LocalDate fxFrom = from.minusDays(7);
        for (Currency currency : foreignCurrencies) {
            fxUni = fxUni.flatMap(map -> marketDataPort.getFxHistory(currency, baseCurrency, fxFrom, to)
                    .map(entries -> {
                        NavigableMap<LocalDate, BigDecimal> series = new TreeMap<>();
                        for (FxRateEntry entry : entries) {
                            series.put(entry.rateDate(), entry.rate());
                        }
                        map.put(currency, series);
                        return map;
                    }));
        }
        return fxUni;
    }

    private List<DailyValuation> buildDailyValuations(
            Map<String, NavigableMap<LocalDate, DailyPositionSnapshot>> byTicker,
            List<String> tickers,
            Map<String, NavigableMap<LocalDate, BigDecimal>> priceMap,
            Map<Currency, NavigableMap<LocalDate, BigDecimal>> fxMap,
            LocalDate from, LocalDate to) {
        List<DailyValuation> valuations = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            BigDecimal marketValue = BigDecimal.ZERO;
            BigDecimal cost = BigDecimal.ZERO;
            boolean complete = true;
            boolean anyPosition = false;
            for (String ticker : tickers) {
                NavigableMap<LocalDate, DailyPositionSnapshot> positionSeries = byTicker.get(ticker);
                Map.Entry<LocalDate, DailyPositionSnapshot> floor = positionSeries.floorEntry(date);
                if (floor == null || floor.getValue().sharesOwned().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                anyPosition = true;
                DailyPositionSnapshot snap = floor.getValue();

                Optional<BigDecimal> price = TimeSeriesLookup.floorWithinStaleness(
                        priceMap.get(ticker), date, PRICE_STALENESS_DAYS);
                Optional<BigDecimal> fxRate = fxRateFor(snap.currency(), fxMap, date);
                if (price.isEmpty() || fxRate.isEmpty()) {
                    complete = false;
                    continue;
                }

                BigDecimal mv = snap.sharesOwned().multiply(price.get()).multiply(fxRate.get())
                        .setScale(6, RoundingMode.HALF_UP);
                marketValue = marketValue.add(mv);
                BigDecimal investedNative = snap.totalInvestedAmount() != null
                        ? snap.totalInvestedAmount()
                        : BigDecimal.ZERO;
                cost = cost.add(investedNative.multiply(fxRate.get()).setScale(6, RoundingMode.HALF_UP));
            }
            if (anyPosition) {
                valuations.add(new DailyValuation(date, baseCurrency, marketValue, cost, complete));
            }
        }
        valuations.sort(Comparator.comparing(DailyValuation::date));
        return valuations;
    }

    private Optional<BigDecimal> fxRateFor(
            Currency tickerCurrency, Map<Currency, NavigableMap<LocalDate, BigDecimal>> fxMap, LocalDate date) {
        if (tickerCurrency == null || tickerCurrency == baseCurrency) {
            return Optional.of(BigDecimal.ONE);
        }
        return TimeSeriesLookup.floorWithinStaleness(fxMap.get(tickerCurrency), date, FX_STALENESS_DAYS);
    }
}

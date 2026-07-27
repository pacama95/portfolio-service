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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
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
    private final long maxRangeDays;

    public GetPortfolioValuationHistoryService(
            TransactionRepository transactionRepository,
            MarketDataPort marketDataPort,
            @ConfigProperty(name = "application.portfolio.base-currency", defaultValue = "USD")
            String baseCurrency,
            @ConfigProperty(name = "application.portfolio.max-query-range-days", defaultValue = "3650")
            long maxRangeDays) {
        this.transactionRepository = transactionRepository;
        this.marketDataPort = marketDataPort;
        this.baseCurrency = Currency.valueOf(baseCurrency);
        this.maxRangeDays = maxRangeDays;
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Getting portfolio valuation history from=%s to=%s", query.from(), query.to());
        if (query.from() == null || query.to() == null || query.from().isAfter(query.to())
                || ChronoUnit.DAYS.between(query.from(), query.to()) > maxRangeDays) {
            return Uni.createFrom().item(new Result.InvalidRequest(
                    "from/to dates are required, from <= to, and range must not exceed " + maxRangeDays + " days"));
        }
        return transactionRepository.findAll(query.userId())
                .flatMap(transactions -> buildValuations(transactions, query.from(), query.to()))
                .invoke(valuations -> LOG.infof("Portfolio valuation history days=%d", valuations.size()))
                .<Result>map(Result.Success::new)
                .onFailure(LedgerReplay.LedgerInconsistencyException.class).recoverWithItem(failure -> {
                    LOG.warnf(failure, "Inconsistent ledger userId=%s", query.userId());
                    return new Result.Conflict(failure.getMessage());
                });
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

        Map<String, HoldingWindow> holdingWindows = activeHoldingWindows(byTicker, from, to);
        if (holdingWindows.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        List<String> tickers = new ArrayList<>(holdingWindows.keySet());
        Map<Currency, LocalDate> foreignCurrencyStarts = new LinkedHashMap<>();
        holdingWindows.values().stream()
                .filter(window -> window.currency() != null && window.currency() != baseCurrency)
                .forEach(window -> foreignCurrencyStarts.merge(
                        window.currency(), window.firstHeldDate(), GetPortfolioValuationHistoryService::earlier));

        return loadPrices(holdingWindows, to)
                .flatMap(priceMap -> loadFxRates(foreignCurrencyStarts, to)
                        .map(fxMap -> buildDailyValuations(byTicker, tickers, priceMap, fxMap, from, to)));
    }

    /**
     * Finds the first day each ticker is actually held inside the requested valuation range.
     * A chart can start years before the first transaction; market-data providers must not be
     * asked for that unused prefix (which can exceed their subscription's history entitlement).
     */
    private Map<String, HoldingWindow> activeHoldingWindows(
            Map<String, NavigableMap<LocalDate, DailyPositionSnapshot>> byTicker,
            LocalDate from,
            LocalDate to) {
        Map<String, HoldingWindow> windows = new LinkedHashMap<>();
        byTicker.forEach((ticker, series) -> firstActiveSnapshot(series, from, to)
                .ifPresent(entry -> windows.put(
                        ticker, new HoldingWindow(entry.getKey(), entry.getValue().currency()))));
        return windows;
    }

    private Optional<Map.Entry<LocalDate, DailyPositionSnapshot>> firstActiveSnapshot(
            NavigableMap<LocalDate, DailyPositionSnapshot> series,
            LocalDate from,
            LocalDate to) {
        Map.Entry<LocalDate, DailyPositionSnapshot> stateAtStart = series.floorEntry(from);
        if (stateAtStart != null && hasShares(stateAtStart.getValue())) {
            return Optional.of(Map.entry(from, stateAtStart.getValue()));
        }
        return series.subMap(from, true, to, true).entrySet().stream()
                .filter(entry -> hasShares(entry.getValue()))
                .findFirst();
    }

    private static boolean hasShares(DailyPositionSnapshot snapshot) {
        return snapshot.sharesOwned().compareTo(BigDecimal.ZERO) > 0;
    }

    private static LocalDate earlier(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    /**
     * Prices are fetched from {@code PRICE_STALENESS_DAYS} before the first day the ticker is held
     * within the query range. This preserves the weekend/holiday floor lookup without requesting
     * history for the potentially much older, unused prefix of the chart range.
     */
    private Uni<Map<String, NavigableMap<LocalDate, BigDecimal>>> loadPrices(
            Map<String, HoldingWindow> holdingWindows, LocalDate to) {
        Uni<Map<String, NavigableMap<LocalDate, BigDecimal>>> pricesUni =
                Uni.createFrom().item(new HashMap<>());
        for (Map.Entry<String, HoldingWindow> holding : holdingWindows.entrySet()) {
            String ticker = holding.getKey();
            LocalDate priceFrom = holding.getValue().firstHeldDate().minusDays(PRICE_STALENESS_DAYS);
            pricesUni = pricesUni.flatMap(map -> marketDataPort.getPriceHistory(ticker, priceFrom, to)
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
     * FX is fetched from a week before the first day any position in that currency is held within
     * the query range. This preserves the weekend/holiday floor lookup without fetching an unused
     * prefix before the portfolio had exposure to that currency.
     */
    private Uni<Map<Currency, NavigableMap<LocalDate, BigDecimal>>> loadFxRates(
            Map<Currency, LocalDate> foreignCurrencyStarts, LocalDate to) {
        Uni<Map<Currency, NavigableMap<LocalDate, BigDecimal>>> fxUni =
                Uni.createFrom().item(new HashMap<>());
        for (Map.Entry<Currency, LocalDate> currencyWindow : foreignCurrencyStarts.entrySet()) {
            Currency currency = currencyWindow.getKey();
            LocalDate fxFrom = currencyWindow.getValue().minusDays(7);
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

                // Cost basis is a pure ledger number and only needs an FX rate to express in base
                // currency — it must not depend on price availability, so it's accumulated as soon
                // as the FX rate is known, before the (possibly-missing) price is even looked up.
                Optional<BigDecimal> fxRate = fxRateFor(snap.currency(), fxMap, date);
                if (fxRate.isEmpty()) {
                    complete = false;
                    continue;
                }
                BigDecimal investedNative = snap.totalInvestedAmount() != null
                        ? snap.totalInvestedAmount()
                        : BigDecimal.ZERO;
                cost = cost.add(investedNative.multiply(fxRate.get()).setScale(6, RoundingMode.HALF_UP));

                Optional<BigDecimal> price = TimeSeriesLookup.floorWithinStaleness(
                        priceMap.get(ticker), date, PRICE_STALENESS_DAYS);
                if (price.isEmpty()) {
                    complete = false;
                    continue;
                }
                BigDecimal mv = snap.sharesOwned().multiply(price.get()).multiply(fxRate.get())
                        .setScale(6, RoundingMode.HALF_UP);
                marketValue = marketValue.add(mv);
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

    private record HoldingWindow(LocalDate firstHeldDate, Currency currency) {
    }
}

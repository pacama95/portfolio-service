package com.portfolio.core.application.usecase.history;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyPositionSnapshot;
import com.portfolio.core.model.DailyValuation;
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
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class GetPortfolioValuationHistoryService implements GetPortfolioValuationHistoryUseCase {

    private static final Logger LOG = Logger.getLogger(GetPortfolioValuationHistoryService.class);

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

        return pricesUni.map(priceMap -> {
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
                    NavigableMap<LocalDate, BigDecimal> series = priceMap.get(ticker);
                    Map.Entry<LocalDate, BigDecimal> priceFloor = series != null ? series.floorEntry(date) : null;
                    if (priceFloor == null || priceFloor.getKey().isBefore(date.minusDays(5))) {
                        complete = false;
                        continue;
                    }
                    BigDecimal mv = snap.sharesOwned().multiply(priceFloor.getValue())
                            .setScale(6, RoundingMode.HALF_UP);
                    marketValue = marketValue.add(mv);
                    cost = cost.add(snap.totalInvestedAmount() != null
                            ? snap.totalInvestedAmount()
                            : BigDecimal.ZERO);
                }
                if (anyPosition) {
                    valuations.add(new DailyValuation(date, baseCurrency, marketValue, cost, complete));
                }
            }
            valuations.sort(Comparator.comparing(DailyValuation::date));
            return valuations;
        });
    }
}

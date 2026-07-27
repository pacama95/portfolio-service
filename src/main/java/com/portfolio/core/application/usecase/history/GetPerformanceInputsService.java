package com.portfolio.core.application.usecase.history;

import com.portfolio.core.domain.TimeSeriesLookup;
import com.portfolio.core.model.CapitalFlowEntry;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.PerformanceInputs;
import com.portfolio.core.ports.incoming.GetCapitalFlowsUseCase;
import com.portfolio.core.ports.incoming.GetPerformanceInputsUseCase;
import com.portfolio.core.ports.incoming.GetPortfolioValuationHistoryUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class GetPerformanceInputsService implements GetPerformanceInputsUseCase {

    private static final Logger LOG = Logger.getLogger(GetPerformanceInputsService.class);
    private static final int FX_STALENESS_DAYS = 5;

    private final GetPortfolioValuationHistoryUseCase valuationHistoryUseCase;
    private final GetCapitalFlowsUseCase capitalFlowsUseCase;
    private final MarketDataPort marketDataPort;
    private final Currency baseCurrency;
    private final long maxRangeDays;

    public GetPerformanceInputsService(
            GetPortfolioValuationHistoryUseCase valuationHistoryUseCase,
            GetCapitalFlowsUseCase capitalFlowsUseCase,
            MarketDataPort marketDataPort,
            @ConfigProperty(name = "application.portfolio.base-currency", defaultValue = "USD")
            String baseCurrency,
            @ConfigProperty(name = "application.portfolio.max-query-range-days", defaultValue = "5500")
            long maxRangeDays) {
        this.valuationHistoryUseCase = valuationHistoryUseCase;
        this.capitalFlowsUseCase = capitalFlowsUseCase;
        this.marketDataPort = marketDataPort;
        this.baseCurrency = Currency.valueOf(baseCurrency);
        this.maxRangeDays = maxRangeDays;
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Getting performance inputs from=%s to=%s", query.from(), query.to());
        if (query.from() == null || query.to() == null || query.from().isAfter(query.to())
                || ChronoUnit.DAYS.between(query.from(), query.to()) > maxRangeDays) {
            return Uni.createFrom().item(new Result.InvalidRequest(
                    "from/to dates are required, from <= to, and range must not exceed " + maxRangeDays + " days"));
        }
        // Sequential: Hibernate Reactive sessions cannot run concurrently on the same Vert.x context.
        return valuationHistoryUseCase.execute(
                        new GetPortfolioValuationHistoryUseCase.Query(
                                query.userId(), query.from(), query.to()))
                .flatMap(valuationResult -> {
                    if (valuationResult instanceof GetPortfolioValuationHistoryUseCase.Result.InvalidRequest invalid) {
                        return Uni.createFrom().item(new Result.InvalidRequest(invalid.message()));
                    }
                    if (valuationResult instanceof GetPortfolioValuationHistoryUseCase.Result.Conflict conflict) {
                        return Uni.createFrom().item(new Result.Conflict(conflict.message()));
                    }
                    return capitalFlowsUseCase.execute(
                                    new GetCapitalFlowsUseCase.Query(query.userId(), query.from(), query.to()))
                            .flatMap(flowsResult -> {
                                if (flowsResult instanceof GetCapitalFlowsUseCase.Result.InvalidRequest invalid) {
                                    return Uni.createFrom().item(new Result.InvalidRequest(invalid.message()));
                                }
                                var valuations =
                                        ((GetPortfolioValuationHistoryUseCase.Result.Success) valuationResult)
                                                .valuations();
                                var flows = ((GetCapitalFlowsUseCase.Result.Success) flowsResult).flows();
                                return convertFlowsToBaseCurrency(flows, query.to())
                                        .map(convertedFlows -> {
                                            int incomplete = (int) valuations.stream()
                                                    .filter(v -> !v.complete()).count();
                                            LOG.infof(
                                                    "Performance inputs valuations=%d flows=%d incomplete=%d",
                                                    valuations.size(), convertedFlows.size(), incomplete);
                                            return new Result.Success(
                                                    new PerformanceInputs(valuations, convertedFlows, incomplete));
                                        });
                            });
                });
    }

    private Uni<List<CapitalFlowEntry>> convertFlowsToBaseCurrency(
            List<CapitalFlowEntry> flows, LocalDate to) {
        Map<Currency, LocalDate> foreignCurrencyStarts = flows.stream()
                .filter(flow -> flow.currency() != null && flow.currency() != baseCurrency)
                .collect(Collectors.toMap(
                        CapitalFlowEntry::currency,
                        CapitalFlowEntry::flowDate,
                        GetPerformanceInputsService::earlier,
                        LinkedHashMap::new));
        if (foreignCurrencyStarts.isEmpty()) {
            return Uni.createFrom().item(flows);
        }
        return loadFxRates(foreignCurrencyStarts, to)
                .map(fxMap -> flows.stream().map(flow -> convert(flow, fxMap)).toList());
    }

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

    private static LocalDate earlier(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private CapitalFlowEntry convert(
            CapitalFlowEntry flow, Map<Currency, NavigableMap<LocalDate, BigDecimal>> fxMap) {
        if (flow.currency() == null || flow.currency() == baseCurrency) {
            return flow;
        }
        Optional<BigDecimal> rate = TimeSeriesLookup.floorWithinStaleness(
                fxMap.get(flow.currency()), flow.flowDate(), FX_STALENESS_DAYS);
        BigDecimal effectiveRate = rate.orElseGet(() -> {
            LOG.warnf(
                    "FX rate unavailable for capital flow currency=%s date=%s; falling back to rate=1",
                    flow.currency(), flow.flowDate());
            return BigDecimal.ONE;
        });
        return new CapitalFlowEntry(
                flow.transactionId(),
                flow.ticker(),
                flow.flowDate(),
                flow.kind(),
                baseCurrency,
                flow.amount().multiply(effectiveRate).setScale(6, RoundingMode.HALF_UP),
                flow.fees() != null ? flow.fees().multiply(effectiveRate).setScale(6, RoundingMode.HALF_UP) : null);
    }
}

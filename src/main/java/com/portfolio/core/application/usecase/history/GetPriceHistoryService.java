package com.portfolio.core.application.usecase.history;

import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.ports.incoming.GetPriceHistoryUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class GetPriceHistoryService implements GetPriceHistoryUseCase {

    private static final Logger LOG = Logger.getLogger(GetPriceHistoryService.class);

    private final MarketDataPort marketDataPort;
    private final long maxRangeDays;

    public GetPriceHistoryService(
            MarketDataPort marketDataPort,
            @ConfigProperty(name = "application.portfolio.max-query-range-days", defaultValue = "5500")
            long maxRangeDays) {
        this.marketDataPort = marketDataPort;
        this.maxRangeDays = maxRangeDays;
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Getting price history symbols=%s from=%s to=%s",
                query.symbols(), query.from(), query.to());
        if (query.from() == null || query.to() == null || query.from().isAfter(query.to())
                || ChronoUnit.DAYS.between(query.from(), query.to()) > maxRangeDays) {
            return Uni.createFrom().item(new Result.InvalidRequest(
                    "from/to dates are required, from <= to, and range must not exceed " + maxRangeDays + " days"));
        }
        if (query.symbols() == null || query.symbols().isEmpty()) {
            return Uni.createFrom().item(new Result.InvalidRequest("symbols are required"));
        }
        Uni<List<PriceHistoryEntry>> acc = Uni.createFrom().item(new ArrayList<>());
        for (String symbol : query.symbols()) {
            acc = acc.flatMap(list -> marketDataPort.getPriceHistory(symbol, query.from(), query.to())
                    .map(entries -> {
                        list.addAll(entries);
                        return list;
                    }));
        }
        return acc.invoke(entries -> LOG.infof("Price history entries=%d", entries.size()))
                .map(Result.Success::new);
    }
}

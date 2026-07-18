package com.portfolio.core.application.usecase.history;

import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.ports.incoming.GetPriceHistoryUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class GetPriceHistoryService implements GetPriceHistoryUseCase {

    private static final Logger LOG = Logger.getLogger(GetPriceHistoryService.class);

    private final MarketDataPort marketDataPort;

    public GetPriceHistoryService(MarketDataPort marketDataPort) {
        this.marketDataPort = marketDataPort;
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Getting price history symbols=%s from=%s to=%s",
                query.symbols(), query.from(), query.to());
        if (query.from() == null || query.to() == null || query.from().isAfter(query.to())) {
            return Uni.createFrom().item(new Result.InvalidRequest("from/to dates are required and from <= to"));
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

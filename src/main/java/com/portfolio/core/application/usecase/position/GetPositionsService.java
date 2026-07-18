package com.portfolio.core.application.usecase.position;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.model.Position;
import com.portfolio.core.ports.incoming.GetPositionsUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class GetPositionsService implements GetPositionsUseCase {

    private static final Logger LOG = Logger.getLogger(GetPositionsService.class);

    private final TransactionRepository transactionRepository;
    private final MarketDataPort marketDataPort;

    public GetPositionsService(TransactionRepository transactionRepository, MarketDataPort marketDataPort) {
        this.transactionRepository = transactionRepository;
        this.marketDataPort = marketDataPort;
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Getting positions activeOnly=%s", query.activeOnly());
        return transactionRepository.findAll(query.userId())
                .map(LedgerReplay::replayAll)
                .map(positions -> query.activeOnly()
                        ? positions.stream().filter(Position::active).toList()
                        : positions)
                .flatMap(this::enrichWithPrices)
                .invoke(positions -> LOG.infof("Returning positions count=%d", positions.size()))
                .map(Result.Success::new);
    }

    private Uni<List<Position>> enrichWithPrices(List<Position> positions) {
        if (positions.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        Uni<List<Position>> acc = Uni.createFrom().item(new ArrayList<>());
        for (Position position : positions) {
            acc = acc.flatMap(list -> marketDataPort.getSpotPrice(position.ticker())
                    .onFailure().recoverWithItem(failure -> {
                        LOG.warnf(failure, "Spot price unavailable ticker=%s; continuing without price",
                                position.ticker());
                        return null;
                    })
                    .map(price -> {
                        list.add(position.withLatestMarketPrice(price));
                        return list;
                    }));
        }
        return acc;
    }
}

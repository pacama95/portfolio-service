package com.portfolio.core.application.usecase.position;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.ports.incoming.GetPositionByTickerUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GetPositionByTickerService implements GetPositionByTickerUseCase {

    private static final Logger LOG = Logger.getLogger(GetPositionByTickerService.class);

    private final TransactionRepository transactionRepository;
    private final MarketDataPort marketDataPort;

    public GetPositionByTickerService(
            TransactionRepository transactionRepository,
            MarketDataPort marketDataPort) {
        this.transactionRepository = transactionRepository;
        this.marketDataPort = marketDataPort;
    }

    @Override
    public Uni<Result> execute(Query query) {
        String ticker = query.ticker().trim().toUpperCase();
        LOG.infof("Getting position ticker=%s", ticker);
        return transactionRepository.findByTicker(query.userId(), ticker)
                .flatMap(transactions -> {
                    if (transactions.isEmpty()) {
                        return Uni.createFrom().item(new Result.NotFound(ticker));
                    }
                    LedgerReplay.ApplyResult replay = LedgerReplay.replayTicker(transactions);
                    if (!(replay instanceof LedgerReplay.ApplyResult.Success success)) {
                        return Uni.createFrom().item(new Result.NotFound(ticker));
                    }
                    return marketDataPort.getSpotPrice(ticker)
                            .onFailure().recoverWithItem(failure -> {
                                LOG.warnf(failure, "Spot price unavailable ticker=%s; continuing without price",
                                        ticker);
                                return null;
                            })
                            .invoke(price -> LOG.infof("Found position ticker=%s hasPrice=%s", ticker, price != null))
                            .map(price -> new Result.Success(success.state().toPosition(ticker, price)));
                });
    }
}

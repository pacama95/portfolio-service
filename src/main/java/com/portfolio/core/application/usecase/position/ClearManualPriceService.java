package com.portfolio.core.application.usecase.position;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.ports.incoming.ClearManualPriceUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ClearManualPriceService implements ClearManualPriceUseCase {

    private static final Logger LOG = Logger.getLogger(ClearManualPriceService.class);

    private final TransactionRepository transactionRepository;
    private final MarketDataPort marketDataPort;

    public ClearManualPriceService(
            TransactionRepository transactionRepository,
            MarketDataPort marketDataPort) {
        this.transactionRepository = transactionRepository;
        this.marketDataPort = marketDataPort;
    }

    @Override
    public Uni<Result> execute(Command command) {
        LOG.infof("Clearing manual price ticker=%s", command.ticker());
        String ticker = command.ticker().trim().toUpperCase();
        return transactionRepository.findByTicker(command.userId(), ticker)
                .flatMap(transactions -> {
                    if (transactions.isEmpty()) {
                        return Uni.createFrom().item(new Result.NotFound(ticker));
                    }
                    LedgerReplay.ApplyResult replay = LedgerReplay.replayTicker(transactions);
                    if (!(replay instanceof LedgerReplay.ApplyResult.Success success)) {
                        return Uni.createFrom().item(new Result.NotFound(ticker));
                    }
                    return marketDataPort.clearManualPrice(ticker)
                            .invoke(() -> LOG.infof("Cleared manual price ticker=%s", ticker))
                            .replaceWith(new Result.Success(success.state().toPosition(ticker, null)));
                });
    }
}

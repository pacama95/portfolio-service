package com.portfolio.core.application.usecase.position;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.ports.incoming.UpdateMarketPriceUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

@ApplicationScoped
public class UpdateMarketPriceService implements UpdateMarketPriceUseCase {

    private static final Logger LOG = Logger.getLogger(UpdateMarketPriceService.class);

    private final TransactionRepository transactionRepository;
    private final MarketDataPort marketDataPort;

    public UpdateMarketPriceService(
            TransactionRepository transactionRepository,
            MarketDataPort marketDataPort) {
        this.transactionRepository = transactionRepository;
        this.marketDataPort = marketDataPort;
    }

    @Override
    public Uni<Result> execute(Command command) {
        LOG.infof("Updating market price ticker=%s price=%s currency=%s",
                command.ticker(), command.price(), command.currency());
        if (command.price() == null || command.price().compareTo(BigDecimal.ZERO) < 0) {
            return Uni.createFrom().item(new Result.InvalidRequest("price cannot be negative"));
        }
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
                    return marketDataPort.setManualPrice(ticker, command.price(), command.currency())
                            .invoke(quote -> LOG.infof("Updated market price ticker=%s value=%s",
                                    ticker, quote.value()))
                            .map(quote -> new Result.Success(
                                    success.state().toPosition(ticker, quote.value())));
                });
    }
}

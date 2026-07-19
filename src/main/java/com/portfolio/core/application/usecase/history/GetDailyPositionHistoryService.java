package com.portfolio.core.application.usecase.history;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.ports.incoming.GetDailyPositionHistoryUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class GetDailyPositionHistoryService implements GetDailyPositionHistoryUseCase {

    private static final Logger LOG = Logger.getLogger(GetDailyPositionHistoryService.class);

    private final TransactionRepository transactionRepository;

    public GetDailyPositionHistoryService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Getting daily position history from=%s to=%s ticker=%s",
                query.from(), query.to(), query.ticker());
        if (query.from() == null || query.to() == null || query.from().isAfter(query.to())) {
            return Uni.createFrom().item(new Result.InvalidRequest("from/to dates are required and from <= to"));
        }
        Uni<List<Transaction>> load = query.ticker() == null || query.ticker().isBlank()
                ? transactionRepository.findAll(query.userId())
                : transactionRepository.findByTicker(query.userId(), query.ticker().trim().toUpperCase());
        return load.<Result>map(transactions -> {
                    var snapshots = LedgerReplay.dailySnapshots(transactions, query.from(), query.to());
                    LOG.infof("Daily position history snapshots=%d", snapshots.size());
                    return new Result.Success(snapshots);
                })
                .onFailure(LedgerReplay.LedgerInconsistencyException.class).recoverWithItem(failure -> {
                    LOG.warnf(failure, "Inconsistent ledger userId=%s", query.userId());
                    return new Result.Conflict(failure.getMessage());
                });
    }
}

package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.ports.incoming.CountTransactionsUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CountTransactionsService implements CountTransactionsUseCase {

    private static final Logger LOG = Logger.getLogger(CountTransactionsService.class);

    private final TransactionRepository transactionRepository;

    public CountTransactionsService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Counting transactions ticker=%s", query.ticker());
        Uni<Long> countUni = query.ticker() == null || query.ticker().isBlank()
                ? transactionRepository.count(query.userId())
                : transactionRepository.countByTicker(query.userId(), query.ticker());
        return countUni
                .invoke(count -> LOG.infof("Transaction count=%d", count))
                .map(Result.Success::new);
    }
}

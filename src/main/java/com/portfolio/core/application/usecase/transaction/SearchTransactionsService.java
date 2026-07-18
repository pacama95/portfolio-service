package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.model.TransactionSortOrder;
import com.portfolio.core.ports.incoming.SearchTransactionsUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SearchTransactionsService implements SearchTransactionsUseCase {

    private static final Logger LOG = Logger.getLogger(SearchTransactionsService.class);

    private final TransactionRepository transactionRepository;

    public SearchTransactionsService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Uni<Result> execute(Query query) {
        TransactionSortOrder sort = query.sortOrder() != null ? query.sortOrder() : TransactionSortOrder.DESC;
        LOG.infof("Searching transactions ticker=%s type=%s from=%s to=%s sort=%s",
                query.ticker(), query.type(), query.fromDate(), query.toDate(), sort);
        return transactionRepository.search(
                        query.userId(),
                        query.ticker(),
                        query.type(),
                        query.fromDate(),
                        query.toDate(),
                        sort)
                .invoke(txs -> LOG.infof("Search returned count=%d", txs.size()))
                .map(Result.Success::new);
    }
}

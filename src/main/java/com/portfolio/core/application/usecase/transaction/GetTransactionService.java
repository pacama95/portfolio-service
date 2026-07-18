package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.ports.incoming.GetTransactionUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GetTransactionService implements GetTransactionUseCase {

    private static final Logger LOG = Logger.getLogger(GetTransactionService.class);

    private final TransactionRepository transactionRepository;

    public GetTransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Getting transaction id=%s", query.id());
        return transactionRepository.findById(query.userId(), query.id())
                .map(optional -> optional
                        .<Result>map(tx -> {
                            LOG.infof("Found transaction id=%s ticker=%s", tx.id(), tx.ticker());
                            return new Result.Success(tx);
                        })
                        .orElseGet(() -> new Result.NotFound(query.id())));
    }
}

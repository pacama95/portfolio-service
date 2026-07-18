package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.ports.incoming.DeleteTransactionUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DeleteTransactionService implements DeleteTransactionUseCase {

    private static final Logger LOG = Logger.getLogger(DeleteTransactionService.class);

    private final TransactionRepository transactionRepository;

    public DeleteTransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Uni<Result> execute(Command command) {
        LOG.infof("Deleting transaction id=%s", command.id());
        return transactionRepository.deleteById(command.userId(), command.id())
                .map(deleted -> {
                    if (deleted) {
                        LOG.infof("Deleted transaction id=%s", command.id());
                        return new Result.Success(command.id());
                    }
                    return new Result.NotFound(command.id());
                });
    }
}

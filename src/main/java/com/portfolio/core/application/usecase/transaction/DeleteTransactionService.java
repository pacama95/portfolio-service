package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.ports.incoming.DeleteTransactionUseCase;
import com.portfolio.core.ports.outgoing.ProviderSymbolMappingPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class DeleteTransactionService implements DeleteTransactionUseCase {

    private static final Logger LOG = Logger.getLogger(DeleteTransactionService.class);

    private final TransactionRepository transactionRepository;
    private final ProviderSymbolMappingPort providerSymbolMappings;

    public DeleteTransactionService(
            TransactionRepository transactionRepository,
            ProviderSymbolMappingPort providerSymbolMappings) {
        this.transactionRepository = transactionRepository;
        this.providerSymbolMappings = providerSymbolMappings;
    }

    @Override
    public Uni<Result> execute(Command command) {
        LOG.infof("Deleting transaction id=%s", command.id());
        return transactionRepository.findById(command.userId(), command.id())
                .flatMap(optional -> {
                    if (optional.isEmpty()) {
                        return Uni.createFrom().item(new Result.NotFound(command.id()));
                    }
                    Transaction target = optional.get();
                    // Locked so a concurrent create/update for the same ticker can't slip in
                    // between this validation and the delete.
                    return transactionRepository.withTickersLocked(
                            command.userId(), List.of(target.ticker()), byTicker -> {
                                List<Transaction> remaining = byTicker.getOrDefault(target.ticker(), List.of())
                                        .stream()
                                        .filter(tx -> !tx.id().equals(target.id()))
                                        .toList();
                                LedgerReplay.ApplyResult replay = LedgerReplay.replayTicker(remaining);
                                if (replay instanceof LedgerReplay.ApplyResult.Oversell oversell) {
                                    return Uni.createFrom().<Result>item(new Result.Conflict(
                                            "Deleting this transaction would oversell " + oversell.ticker()
                                                    + ": requested " + oversell.requested()
                                                    + " available " + oversell.available()));
                                }
                                return transactionRepository.deleteById(command.userId(), command.id())
                                        .<Result>map(deleted -> {
                                            if (deleted) {
                                                LOG.infof("Deleted transaction id=%s", command.id());
                                                return new Result.Success(command.id());
                                            }
                                            return new Result.NotFound(command.id());
                                        });
                            })
                            .call(result -> result instanceof Result.Success
                                    ? providerSymbolMappings.invalidate(target.ticker())
                                    : Uni.createFrom().voidItem());
                });
    }
}

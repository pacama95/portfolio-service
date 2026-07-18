package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.model.Transaction;
import com.portfolio.core.ports.incoming.UpdateTransactionUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@ApplicationScoped
public class UpdateTransactionService implements UpdateTransactionUseCase {

    private static final Logger LOG = Logger.getLogger(UpdateTransactionService.class);

    private final TransactionRepository transactionRepository;

    public UpdateTransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Uni<Result> execute(Command command) {
        LOG.infof("Updating transaction id=%s", command.id());

        if (command.quantity() != null && command.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            return Uni.createFrom().item(new Result.InvalidRequest("quantity must be positive"));
        }
        if (command.price() != null && command.price().compareTo(BigDecimal.ZERO) < 0) {
            return Uni.createFrom().item(new Result.InvalidRequest("price cannot be negative"));
        }

        return transactionRepository.findById(command.userId(), command.id())
                .flatMap(optional -> {
                    if (optional.isEmpty()) {
                        return Uni.createFrom().item(new Result.NotFound(command.id()));
                    }
                    Transaction existing = optional.get();
                    Transaction updated = new Transaction(
                            existing.id(),
                            existing.userId(),
                            command.ticker() != null ? command.ticker().trim().toUpperCase() : existing.ticker(),
                            command.transactionType() != null ? command.transactionType() : existing.transactionType(),
                            existing.assetType(),
                            command.quantity() != null ? command.quantity() : existing.quantity(),
                            command.price() != null ? command.price() : existing.price(),
                            command.fees() != null ? command.fees() : existing.fees(),
                            command.currency() != null ? command.currency() : existing.currency(),
                            command.transactionDate() != null ? command.transactionDate() : existing.transactionDate(),
                            command.notes() != null ? command.notes() : existing.notes(),
                            command.fractional() != null ? command.fractional() : existing.fractional(),
                            command.fractionalMultiplier() != null
                                    ? command.fractionalMultiplier()
                                    : existing.fractionalMultiplier(),
                            command.commissionCurrency() != null
                                    ? command.commissionCurrency()
                                    : existing.commissionCurrency(),
                            command.exchange() != null ? command.exchange() : existing.exchange(),
                            command.country() != null ? command.country() : existing.country(),
                            command.companyName() != null ? command.companyName() : existing.companyName(),
                            existing.createdAt(),
                            OffsetDateTime.now());
                    return transactionRepository.save(updated)
                            .invoke(saved -> LOG.infof("Updated transaction id=%s ticker=%s", saved.id(), saved.ticker()))
                            .map(Result.Success::new);
                });
    }
}

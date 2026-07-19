package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.ports.incoming.UpdateTransactionUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
                    return validateAndSave(existing, updated);
                });
    }

    /**
     * Replays the affected ledger(s) with the edit applied before persisting it. A ticker
     * change is validated on both sides: the old ticker without this transaction (removing/
     * moving it must not oversell what's left behind) and the new ticker with it added. Both
     * tickers are locked together so a concurrent write for either can't slip in between this
     * validation and the save.
     */
    private Uni<Result> validateAndSave(Transaction existing, Transaction updated) {
        return transactionRepository.withTickersLocked(
                existing.userId(), List.of(existing.ticker(), updated.ticker()), byTicker -> {
                    List<Transaction> oldTickerRemaining = byTicker.getOrDefault(existing.ticker(), List.of())
                            .stream()
                            .filter(tx -> !tx.id().equals(existing.id()))
                            .toList();

                    if (updated.ticker().equals(existing.ticker())) {
                        List<Transaction> combined = new ArrayList<>(oldTickerRemaining);
                        combined.add(updated);
                        return rejectionFor(LedgerReplay.replayTicker(combined), updated)
                                .<Uni<Result>>map(Uni.createFrom()::item)
                                .orElseGet(() -> save(updated));
                    }

                    Optional<Result> oldTickerRejection =
                            rejectionFor(LedgerReplay.replayTicker(oldTickerRemaining), existing);
                    if (oldTickerRejection.isPresent()) {
                        return Uni.createFrom().item(oldTickerRejection.get());
                    }
                    List<Transaction> combined = new ArrayList<>(byTicker.getOrDefault(updated.ticker(), List.of()));
                    combined.add(updated);
                    return rejectionFor(LedgerReplay.replayTicker(combined), updated)
                            .<Uni<Result>>map(Uni.createFrom()::item)
                            .orElseGet(() -> save(updated));
                });
    }

    private Uni<Result> save(Transaction updated) {
        return transactionRepository.save(updated)
                .invoke(saved -> LOG.infof("Updated transaction id=%s ticker=%s", saved.id(), saved.ticker()))
                .map(Result.Success::new);
    }

    private static Optional<Result> rejectionFor(LedgerReplay.ApplyResult replay, Transaction subject) {
        if (replay instanceof LedgerReplay.ApplyResult.Oversell oversell) {
            return Optional.of(new Result.Conflict(
                    "Would oversell " + oversell.ticker() + ": requested " + oversell.requested()
                            + " available " + oversell.available() + " as of " + subject.transactionDate()));
        }
        if (replay instanceof LedgerReplay.ApplyResult.InvalidInput invalid) {
            return Optional.of(new Result.InvalidRequest(invalid.message()));
        }
        return Optional.empty();
    }
}

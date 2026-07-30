package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.event.TransactionCreatedEvent;
import com.portfolio.core.ports.incoming.CreateTransactionUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.ProviderSymbolMappingPort;
import com.portfolio.core.ports.outgoing.TransactionEventPublisher;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CreateTransactionService implements CreateTransactionUseCase {

    private static final Logger LOG = Logger.getLogger(CreateTransactionService.class);

    private final TransactionRepository transactionRepository;
    private final TransactionEventPublisher eventPublisher;
    private final MarketDataPort marketDataPort;
    private final ProviderSymbolMappingPort providerSymbolMappings;

    public CreateTransactionService(
            TransactionRepository transactionRepository,
            TransactionEventPublisher eventPublisher,
            MarketDataPort marketDataPort,
            ProviderSymbolMappingPort providerSymbolMappings) {
        this.transactionRepository = transactionRepository;
        this.eventPublisher = eventPublisher;
        this.marketDataPort = marketDataPort;
        this.providerSymbolMappings = providerSymbolMappings;
    }

    @Override
    public Uni<Result> execute(Command command) {
        LOG.infof("Creating transaction ticker=%s type=%s quantity=%s",
                command.ticker(), command.transactionType(), command.quantity());

        if (command.ticker() == null || command.ticker().isBlank()) {
            return Uni.createFrom().item(new Result.InvalidRequest("ticker is required"));
        }
        if (command.quantity() == null || command.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            return Uni.createFrom().item(new Result.InvalidRequest("quantity must be positive"));
        }
        if (command.price() == null || command.price().compareTo(BigDecimal.ZERO) < 0) {
            return Uni.createFrom().item(new Result.InvalidRequest("price cannot be negative"));
        }
        if (command.transactionType() == null || command.assetType() == null
                || command.currency() == null || command.transactionDate() == null) {
            return Uni.createFrom().item(new Result.InvalidRequest("required fields missing"));
        }
        // Exchange and country are what let a market-data provider resolve a bare ticker to one
        // specific listing. Accepting a transaction without them defers the failure to pricing.
        if (command.exchange() == null || command.exchange().isBlank()) {
            return Uni.createFrom().item(new Result.InvalidRequest("exchange is required"));
        }
        if (command.country() == null || command.country().isBlank()) {
            return Uni.createFrom().item(new Result.InvalidRequest("country is required"));
        }

        BigDecimal rawFees = command.fees() != null ? command.fees() : BigDecimal.ZERO;
        return convertFees(command.commissionCurrency(), command.currency(), rawFees)
                .flatMap(fees -> createWithFees(command, fees));
    }

    private Uni<Result> createWithFees(Command command, BigDecimal fees) {
        OffsetDateTime now = OffsetDateTime.now();
        Transaction transaction = new Transaction(
                UUID.randomUUID(),
                command.userId(),
                command.ticker().trim().toUpperCase(),
                command.transactionType(),
                command.assetType(),
                command.quantity(),
                command.price(),
                fees,
                command.currency(),
                command.transactionDate(),
                command.notes(),
                Boolean.TRUE.equals(command.fractional()),
                command.fractionalMultiplier() != null ? command.fractionalMultiplier() : BigDecimal.ONE,
                command.commissionCurrency(),
                command.exchange(),
                command.country(),
                command.companyName(),
                now,
                now);

        // Locked so a concurrent create/update/delete for the same ticker can't slip in between
        // this validation and the save (see TransactionRepository.withTickersLocked).
        return transactionRepository.withTickersLocked(
                command.userId(), List.of(transaction.ticker()), byTicker -> {
                    List<Transaction> existing = byTicker.getOrDefault(transaction.ticker(), List.of());
                    List<Transaction> combined = new ArrayList<>(existing);
                    combined.add(transaction);
                    LedgerReplay.ApplyResult replay = LedgerReplay.replayTicker(combined);
                    if (replay instanceof LedgerReplay.ApplyResult.Oversell(
                            String ticker, BigDecimal requested, BigDecimal available
                    )) {
                        return Uni.createFrom().<Result>item(new Result.Conflict(
                                "Would oversell " + ticker + ": requested " + requested
                                        + " available " + available
                                        + " as of " + transaction.transactionDate()));
                    }
                    if (replay instanceof LedgerReplay.ApplyResult.InvalidInput(String message)) {
                        return Uni.createFrom().item(new Result.InvalidRequest(message));
                    }
                    return transactionRepository.save(transaction)
                            .invoke(saved -> LOG.infof(
                                    "Created transaction id=%s ticker=%s", saved.id(), saved.ticker()))
                            .map(Result.Success::new);
                })
                .call(result -> result instanceof Result.Success(Transaction saved)
                        ? providerSymbolMappings.invalidate(saved.ticker()).chain(() -> publishCreatedEvent(saved))
                        : Uni.createFrom().voidItem());
    }

    private Uni<BigDecimal> convertFees(Currency commissionCurrency, Currency currency, BigDecimal fees) {
        if (commissionCurrency == null || commissionCurrency == currency || fees.compareTo(BigDecimal.ZERO) == 0) {
            return Uni.createFrom().item(fees);
        }
        return marketDataPort.getFxRate(commissionCurrency, currency)
                .onFailure().recoverWithItem(failure -> {
                    // TODO: this is wrong, we cannot assume the FX rate is one, what happen if the provider is down for some time or this endpoint fails? We need to fully fail in this case, FX is core.
                    LOG.warnf(failure, "FX rate unavailable commissionCurrency=%s currency=%s; "
                            + "falling back to rate=1 for fee conversion", commissionCurrency, currency);
                    return BigDecimal.ONE;
                })
                .map(rate -> fees.multiply(rate).setScale(6, RoundingMode.HALF_UP));
    }

    private Uni<Void> publishCreatedEvent(Transaction transaction) {
        return eventPublisher.publishTransactionCreated(TransactionCreatedEvent.from(transaction))
                .onFailure().recoverWithItem(failure -> {
                    LOG.warnf(failure, "Failed to publish transaction.created event for transaction id=%s; continuing",
                            transaction.id());
                    return null;
                });
    }
}

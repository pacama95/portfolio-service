package com.portfolio.core.application.usecase.marketdata;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.model.IngestionRun;
import com.portfolio.core.model.Position;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.TriggerPriceIngestionUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.PriceIngestionRunRepository;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class TriggerPriceIngestionService implements TriggerPriceIngestionUseCase {

    private static final Logger LOG = Logger.getLogger(TriggerPriceIngestionService.class);

    private final TransactionRepository transactionRepository;
    private final MarketDataPort marketDataPort;
    private final PriceIngestionRunRepository runRepository;
    private final Duration staleRunTimeout;
    private final Duration symbolFetchDelay;

    public TriggerPriceIngestionService(
            TransactionRepository transactionRepository,
            MarketDataPort marketDataPort,
            PriceIngestionRunRepository runRepository,
            @ConfigProperty(name = "application.market-data.ingestion.stale-run-timeout", defaultValue = "PT3H")
            Duration staleRunTimeout,
            @ConfigProperty(name = "application.market-data.ingestion.symbol-fetch-delay", defaultValue = "PT8S")
            Duration symbolFetchDelay) {
        this.transactionRepository = transactionRepository;
        this.marketDataPort = marketDataPort;
        this.runRepository = runRepository;
        this.staleRunTimeout = staleRunTimeout;
        this.symbolFetchDelay = symbolFetchDelay;
    }

    @Override
    public Uni<Result> execute(Command command) {
        LOG.infof(
                "Triggering price ingestion from=%s to=%s ticker=%s includeBackfill=%s",
                command.from(), command.to(), command.ticker(), command.includeBackfill());
        if (command.from() == null || command.to() == null || command.from().isAfter(command.to())) {
            return Uni.createFrom().item(new Result.InvalidRequest("from/to dates are required and from <= to"));
        }
        OffsetDateTime now = OffsetDateTime.now();
        IngestionRun candidate = new IngestionRun(
                UUID.randomUUID(),
                command.userId(),
                IngestionRun.Status.PENDING,
                command.from(),
                command.to(),
                command.ticker(),
                command.includeBackfill(),
                0,
                0,
                null,
                null,
                null,
                now,
                now);
        return runRepository.startRunIfNoneActive(staleRunTimeout, candidate)
                .map(maybeSaved -> {
                    if (maybeSaved.isEmpty()) {
                        return new Result.Conflict("An ingestion run is already in progress");
                    }
                    IngestionRun saved = maybeSaved.get();
                    LOG.infof("Accepted price ingestion runId=%s", saved.id());
                    startAsync(saved, command);
                    return new Result.Accepted(saved.id());
                });
    }

    private void startAsync(IngestionRun run, Command command) {
        Uni.createFrom().voidItem()
                .chain(() -> runIngestion(run, command))
                .subscribe().with(
                        ignored -> LOG.infof("Price ingestion completed runId=%s", run.id()),
                        failure -> LOG.errorf(failure, "Price ingestion failed runId=%s", run.id()));
    }

    Uni<Void> runIngestion(IngestionRun initial, Command command) {
        OffsetDateTime now = OffsetDateTime.now();
        IngestionRun running = withStatus(initial, IngestionRun.Status.RUNNING, now, null, null);
        return runRepository.save(running)
                .invoke(saved -> LOG.infof("Price ingestion running runId=%s", saved.id()))
                .chain(() -> resolveSymbols(command))
                .flatMap(symbols -> {
                    LOG.infof("Price ingestion symbolsRequested=%d runId=%s", symbols.size(), running.id());
                    IngestionRun requested = new IngestionRun(
                            running.id(),
                            running.userId(),
                            running.status(),
                            running.fromDate(),
                            running.toDate(),
                            running.ticker(),
                            running.includeBackfill(),
                            symbols.size(),
                            0,
                            null,
                            running.startedAt() != null ? running.startedAt() : now,
                            null,
                            running.createdAt(),
                            OffsetDateTime.now());
                    return runRepository.save(requested)
                            .chain(() -> ingestSymbols(requested, symbols, command.from(), command.to()));
                })
                .onFailure().call(failure -> runRepository.save(withStatus(
                        initial, IngestionRun.Status.FAILED, initial.startedAt(),
                        OffsetDateTime.now(), failure.getMessage())).replaceWithVoid());
    }

    private Uni<List<String>> resolveSymbols(Command command) {
        if (command.ticker() != null && !command.ticker().isBlank()) {
            return Uni.createFrom().item(List.of(command.ticker().trim().toUpperCase()));
        }
        return transactionRepository.findAll().map(this::tickersWithOpenPositions);
    }

    /**
     * Scopes the unbounded (cron-driven) ingestion to tickers someone actually still holds,
     * instead of every ticker any user ever typed. Position state depends on transaction order
     * (BUY/SELL/SPLIT), so this replays each user's ledger rather than doing a naive SQL sum.
     */
    private List<String> tickersWithOpenPositions(List<Transaction> allTransactions) {
        Map<UserId, List<Transaction>> byUser = allTransactions.stream()
                .collect(Collectors.groupingBy(Transaction::userId));
        TreeSet<String> tickers = new TreeSet<>();
        for (Map.Entry<UserId, List<Transaction>> entry : byUser.entrySet()) {
            try {
                for (Position position : LedgerReplay.replayAll(entry.getValue())) {
                    if (position.active()) {
                        tickers.add(position.ticker());
                    }
                }
            } catch (LedgerReplay.LedgerInconsistencyException e) {
                LOG.warnf(e, "Skipping ledger-inconsistent userId=%s when scoping ingestion tickers",
                        entry.getKey().value());
            }
        }
        return List.copyOf(tickers);
    }

    private Uni<Void> ingestSymbols(IngestionRun run, List<String> symbols, LocalDate from, LocalDate to) {
        List<String> failures = new ArrayList<>();
        Uni<IngestionRun> current = Uni.createFrom().item(run);
        for (int i = 0; i < symbols.size(); i++) {
            String symbol = symbols.get(i);
            Uni<Void> delay = i == 0
                    ? Uni.createFrom().voidItem()
                    : Uni.createFrom().voidItem().onItem().delayIt().by(symbolFetchDelay);
            current = current.flatMap(r -> {
                LOG.infof("Ingesting symbol=%s from=%s to=%s runId=%s", symbol, from, to, r.id());
                return delay
                        .chain(() -> marketDataPort.getPriceHistory(symbol, from, to))
                        .replaceWith(true)
                        .onFailure().recoverWithItem(failure -> {
                            LOG.warnf(failure, "Symbol ingestion failed symbol=%s runId=%s", symbol, r.id());
                            failures.add(symbol + ": " + failure.getMessage());
                            return false;
                        })
                        .flatMap(succeeded -> {
                            IngestionRun updated = new IngestionRun(
                                    r.id(),
                                    r.userId(),
                                    r.status(),
                                    r.fromDate(),
                                    r.toDate(),
                                    r.ticker(),
                                    r.includeBackfill(),
                                    r.symbolsRequested(),
                                    Boolean.TRUE.equals(succeeded) ? r.symbolsCompleted() + 1 : r.symbolsCompleted(),
                                    null,
                                    r.startedAt(),
                                    null,
                                    r.createdAt(),
                                    OffsetDateTime.now());
                            return runRepository.save(updated);
                        });
            });
        }
        return current.flatMap(r -> {
            IngestionRun.Status finalStatus = failures.isEmpty()
                    ? IngestionRun.Status.COMPLETED
                    : IngestionRun.Status.PARTIAL;
            String errorSummary = failures.isEmpty()
                    ? null
                    : failures.size() + " of " + symbols.size() + " symbols failed: "
                            + String.join("; ", failures);
            return runRepository.save(withStatus(r, finalStatus, r.startedAt(), OffsetDateTime.now(), errorSummary));
        })
                .invoke(completed -> LOG.infof(
                        "Price ingestion marked %s runId=%s symbolsCompleted=%d",
                        completed.status(), completed.id(), completed.symbolsCompleted()))
                .replaceWithVoid();
    }

    private static IngestionRun withStatus(
            IngestionRun run,
            IngestionRun.Status status,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            String error) {
        return new IngestionRun(
                run.id(),
                run.userId(),
                status,
                run.fromDate(),
                run.toDate(),
                run.ticker(),
                run.includeBackfill(),
                run.symbolsRequested(),
                run.symbolsCompleted(),
                error,
                startedAt != null ? startedAt : run.startedAt(),
                completedAt,
                run.createdAt(),
                OffsetDateTime.now());
    }
}

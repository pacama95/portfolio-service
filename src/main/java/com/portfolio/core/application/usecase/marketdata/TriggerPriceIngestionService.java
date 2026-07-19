package com.portfolio.core.application.usecase.marketdata;

import com.portfolio.core.model.IngestionRun;
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
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class TriggerPriceIngestionService implements TriggerPriceIngestionUseCase {

    private static final Logger LOG = Logger.getLogger(TriggerPriceIngestionService.class);

    private final TransactionRepository transactionRepository;
    private final MarketDataPort marketDataPort;
    private final PriceIngestionRunRepository runRepository;
    private final Duration staleRunTimeout;

    public TriggerPriceIngestionService(
            TransactionRepository transactionRepository,
            MarketDataPort marketDataPort,
            PriceIngestionRunRepository runRepository,
            @ConfigProperty(name = "application.market-data.ingestion.stale-run-timeout", defaultValue = "PT3H")
            Duration staleRunTimeout) {
        this.transactionRepository = transactionRepository;
        this.marketDataPort = marketDataPort;
        this.runRepository = runRepository;
        this.staleRunTimeout = staleRunTimeout;
    }

    @Override
    public Uni<Result> execute(Command command) {
        LOG.infof(
                "Triggering price ingestion from=%s to=%s ticker=%s includeBackfill=%s",
                command.from(), command.to(), command.ticker(), command.includeBackfill());
        if (command.from() == null || command.to() == null || command.from().isAfter(command.to())) {
            return Uni.createFrom().item(new Result.InvalidRequest("from/to dates are required and from <= to"));
        }
        return runRepository.hasActiveRun(staleRunTimeout)
                .flatMap(running -> {
                    if (Boolean.TRUE.equals(running)) {
                        return Uni.createFrom().item(new Result.Conflict("An ingestion run is already in progress"));
                    }
                    OffsetDateTime now = OffsetDateTime.now();
                    IngestionRun run = new IngestionRun(
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
                    return runRepository.save(run)
                            .invoke(saved -> {
                                LOG.infof("Accepted price ingestion runId=%s", saved.id());
                                startAsync(saved, command);
                            })
                            .map(saved -> new Result.Accepted(saved.id()));
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
        return transactionRepository.findDistinctTickers();
    }

    private Uni<Void> ingestSymbols(IngestionRun run, List<String> symbols, LocalDate from, LocalDate to) {
        Uni<IngestionRun> current = Uni.createFrom().item(run);
        for (String symbol : symbols) {
            current = current.flatMap(r -> {
                LOG.infof("Ingesting symbol=%s from=%s to=%s runId=%s", symbol, from, to, r.id());
                return marketDataPort.getPriceHistory(symbol, from, to)
                        .replaceWith(r)
                        .flatMap(prev -> {
                            IngestionRun updated = new IngestionRun(
                                    prev.id(),
                                    prev.userId(),
                                    prev.status(),
                                    prev.fromDate(),
                                    prev.toDate(),
                                    prev.ticker(),
                                    prev.includeBackfill(),
                                    prev.symbolsRequested(),
                                    prev.symbolsCompleted() + 1,
                                    null,
                                    prev.startedAt(),
                                    null,
                                    prev.createdAt(),
                                    OffsetDateTime.now());
                            return runRepository.save(updated);
                        });
            });
        }
        return current.flatMap(r -> runRepository.save(withStatus(
                r, IngestionRun.Status.COMPLETED, r.startedAt(), OffsetDateTime.now(), null)))
                .invoke(completed -> LOG.infof(
                        "Price ingestion marked completed runId=%s symbolsCompleted=%d",
                        completed.id(), completed.symbolsCompleted()))
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

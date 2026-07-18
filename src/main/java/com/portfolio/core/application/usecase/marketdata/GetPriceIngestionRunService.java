package com.portfolio.core.application.usecase.marketdata;

import com.portfolio.core.ports.incoming.GetPriceIngestionRunUseCase;
import com.portfolio.core.ports.outgoing.PriceIngestionRunRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GetPriceIngestionRunService implements GetPriceIngestionRunUseCase {

    private static final Logger LOG = Logger.getLogger(GetPriceIngestionRunService.class);

    private final PriceIngestionRunRepository runRepository;

    public GetPriceIngestionRunService(PriceIngestionRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.info("Getting latest price ingestion run");
        return runRepository.findLatest()
                .map(optional -> optional
                        .<Result>map(run -> {
                            LOG.infof("Found ingestion run id=%s status=%s", run.id(), run.status());
                            return new Result.Success(new RunStatus(
                                    run.id(),
                                    run.status().name(),
                                    run.fromDate(),
                                    run.toDate(),
                                    run.ticker(),
                                    run.symbolsRequested(),
                                    run.symbolsCompleted(),
                                    run.errorMessage(),
                                    run.startedAt(),
                                    run.completedAt()));
                        })
                        .orElseGet(Result.NotFound::new));
    }
}

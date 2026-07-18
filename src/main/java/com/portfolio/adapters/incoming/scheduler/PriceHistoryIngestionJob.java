package com.portfolio.adapters.incoming.scheduler;

import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.TriggerPriceIngestionUseCase;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.time.LocalDate;

@ApplicationScoped
public class PriceHistoryIngestionJob {

    private static final Logger LOG = Logger.getLogger(PriceHistoryIngestionJob.class);
    private static final String SYSTEM_USER = "system";
    private static final String USER_ID_MDC_KEY = "userId";

    private final TriggerPriceIngestionUseCase triggerPriceIngestionUseCase;

    public PriceHistoryIngestionJob(TriggerPriceIngestionUseCase triggerPriceIngestionUseCase) {
        this.triggerPriceIngestionUseCase = triggerPriceIngestionUseCase;
    }

    @Scheduled(cron = "0 0 6 * * ?")
    @WithSpan("price-history-ingestion-job")
    void ingestPreviousDay() {
        MDC.put(USER_ID_MDC_KEY, SYSTEM_USER);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LOG.infof("Starting scheduled price ingestion for date=%s", yesterday);
        triggerPriceIngestionUseCase.execute(new TriggerPriceIngestionUseCase.Command(
                        UserId.of(SYSTEM_USER),
                        yesterday,
                        yesterday,
                        null,
                        false))
                .subscribe().with(
                        result -> {
                            LOG.infof("Scheduled ingestion result=%s", result);
                            MDC.remove(USER_ID_MDC_KEY);
                        },
                        failure -> {
                            LOG.error("Scheduled ingestion failed", failure);
                            MDC.remove(USER_ID_MDC_KEY);
                        });
    }
}

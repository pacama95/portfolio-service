package com.portfolio.adapters.incoming.scheduler;

import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.TriggerPriceIngestionUseCase;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceHistoryIngestionJobTest {

    private final TriggerPriceIngestionUseCase triggerPriceIngestionUseCase =
            Mockito.mock(TriggerPriceIngestionUseCase.class);
    private PriceHistoryIngestionJob job;

    @BeforeEach
    void setUp() {
        job = new PriceHistoryIngestionJob(triggerPriceIngestionUseCase);
    }

    @Test
    void givenScheduledRun_whenIngestPreviousDay_thenTriggersForSystemUserAndYesterday() {
        when(triggerPriceIngestionUseCase.execute(any(TriggerPriceIngestionUseCase.Command.class)))
                .thenReturn(Uni.createFrom().item(new TriggerPriceIngestionUseCase.Result.Accepted(
                        java.util.UUID.randomUUID())));

        job.ingestPreviousDay();

        ArgumentCaptor<TriggerPriceIngestionUseCase.Command> captor =
                ArgumentCaptor.forClass(TriggerPriceIngestionUseCase.Command.class);
        verify(triggerPriceIngestionUseCase).execute(captor.capture());
        TriggerPriceIngestionUseCase.Command command = captor.getValue();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        org.junit.jupiter.api.Assertions.assertEquals(UserId.of("system"), command.userId());
        org.junit.jupiter.api.Assertions.assertEquals(yesterday, command.from());
        org.junit.jupiter.api.Assertions.assertEquals(yesterday, command.to());
        org.junit.jupiter.api.Assertions.assertNull(command.ticker());
        org.junit.jupiter.api.Assertions.assertFalse(command.includeBackfill());
    }

    @Test
    void givenIngestionFailure_whenIngestPreviousDay_thenSubscribesWithoutPropagatingFailure() {
        when(triggerPriceIngestionUseCase.execute(any(TriggerPriceIngestionUseCase.Command.class)))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("scheduled failure")));

        job.ingestPreviousDay();

        verify(triggerPriceIngestionUseCase).execute(any(TriggerPriceIngestionUseCase.Command.class));
    }
}

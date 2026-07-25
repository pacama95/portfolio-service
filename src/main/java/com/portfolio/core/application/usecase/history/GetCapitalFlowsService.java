package com.portfolio.core.application.usecase.history;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.ports.incoming.GetCapitalFlowsUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class GetCapitalFlowsService implements GetCapitalFlowsUseCase {

    private static final Logger LOG = Logger.getLogger(GetCapitalFlowsService.class);

    private final TransactionRepository transactionRepository;
    private final long maxRangeDays;

    public GetCapitalFlowsService(
            TransactionRepository transactionRepository,
            @ConfigProperty(name = "application.portfolio.max-query-range-days", defaultValue = "3650")
            long maxRangeDays) {
        this.transactionRepository = transactionRepository;
        this.maxRangeDays = maxRangeDays;
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Getting capital flows from=%s to=%s", query.from(), query.to());
        if (query.from() == null || query.to() == null || query.from().isAfter(query.to())
                || ChronoUnit.DAYS.between(query.from(), query.to()) > maxRangeDays) {
            return Uni.createFrom().item(new Result.InvalidRequest(
                    "from/to dates are required, from <= to, and range must not exceed " + maxRangeDays + " days"));
        }
        return transactionRepository.findAll(query.userId())
                .map(txs -> LedgerReplay.capitalFlows(txs, query.from(), query.to()))
                .invoke(flows -> LOG.infof("Capital flows count=%d", flows.size()))
                .map(Result.Success::new);
    }
}

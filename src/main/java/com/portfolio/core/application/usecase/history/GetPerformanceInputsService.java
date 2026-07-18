package com.portfolio.core.application.usecase.history;

import com.portfolio.core.model.PerformanceInputs;
import com.portfolio.core.ports.incoming.GetCapitalFlowsUseCase;
import com.portfolio.core.ports.incoming.GetPerformanceInputsUseCase;
import com.portfolio.core.ports.incoming.GetPortfolioValuationHistoryUseCase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GetPerformanceInputsService implements GetPerformanceInputsUseCase {

    private static final Logger LOG = Logger.getLogger(GetPerformanceInputsService.class);

    private final GetPortfolioValuationHistoryUseCase valuationHistoryUseCase;
    private final GetCapitalFlowsUseCase capitalFlowsUseCase;

    public GetPerformanceInputsService(
            GetPortfolioValuationHistoryUseCase valuationHistoryUseCase,
            GetCapitalFlowsUseCase capitalFlowsUseCase) {
        this.valuationHistoryUseCase = valuationHistoryUseCase;
        this.capitalFlowsUseCase = capitalFlowsUseCase;
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Getting performance inputs from=%s to=%s", query.from(), query.to());
        if (query.from() == null || query.to() == null || query.from().isAfter(query.to())) {
            return Uni.createFrom().item(new Result.InvalidRequest("from/to dates are required and from <= to"));
        }
        // Sequential: Hibernate Reactive sessions cannot run concurrently on the same Vert.x context.
        return valuationHistoryUseCase.execute(
                        new GetPortfolioValuationHistoryUseCase.Query(
                                query.userId(), query.from(), query.to()))
                .flatMap(valuationResult -> {
                    if (valuationResult instanceof GetPortfolioValuationHistoryUseCase.Result.InvalidRequest invalid) {
                        return Uni.createFrom().item(new Result.InvalidRequest(invalid.message()));
                    }
                    return capitalFlowsUseCase.execute(
                                    new GetCapitalFlowsUseCase.Query(query.userId(), query.from(), query.to()))
                            .map(flowsResult -> {
                                if (flowsResult instanceof GetCapitalFlowsUseCase.Result.InvalidRequest invalid) {
                                    return new Result.InvalidRequest(invalid.message());
                                }
                                var valuations =
                                        ((GetPortfolioValuationHistoryUseCase.Result.Success) valuationResult)
                                                .valuations();
                                var flows = ((GetCapitalFlowsUseCase.Result.Success) flowsResult).flows();
                                int incomplete = (int) valuations.stream().filter(v -> !v.complete()).count();
                                LOG.infof(
                                        "Performance inputs valuations=%d flows=%d incomplete=%d",
                                        valuations.size(), flows.size(), incomplete);
                                return new Result.Success(new PerformanceInputs(valuations, flows, incomplete));
                            });
                });
    }
}

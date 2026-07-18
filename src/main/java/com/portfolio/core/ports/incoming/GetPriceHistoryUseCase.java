package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.time.LocalDate;
import java.util.List;

public interface GetPriceHistoryUseCase {

    Uni<Result> execute(Query query);

    sealed interface Result {
        record Success(List<PriceHistoryEntry> entries) implements Result {}
        record InvalidRequest(String message) implements Result {}
    }

    record Query(UserId userId, List<String> symbols, LocalDate from, LocalDate to) {
    }
}

package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.util.UUID;

public interface GetTransactionUseCase {

    Uni<Result> execute(Query query);

    sealed interface Result {
        record Success(Transaction transaction) implements Result {}
        record NotFound(UUID id) implements Result {}
    }

    record Query(UserId userId, UUID id) {
    }
}

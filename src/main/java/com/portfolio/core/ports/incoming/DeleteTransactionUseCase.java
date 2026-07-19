package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.util.UUID;

public interface DeleteTransactionUseCase {

    Uni<Result> execute(Command command);

    sealed interface Result {
        record Success(UUID id) implements Result {}
        record NotFound(UUID id) implements Result {}
        record Conflict(String message) implements Result {}
    }

    record Command(UserId userId, UUID id) {
    }
}

package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.Position;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

public interface ClearManualPriceUseCase {

    Uni<Result> execute(Command command);

    sealed interface Result {
        record Success(Position position) implements Result {}
        record NotFound(String ticker) implements Result {}
    }

    record Command(UserId userId, String ticker) {
    }
}

package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.Position;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

public interface GetPositionByTickerUseCase {

    Uni<Result> execute(Query query);

    sealed interface Result {
        record Success(Position position) implements Result {}
        record NotFound(String ticker) implements Result {}
    }

    record Query(UserId userId, String ticker) {
    }
}

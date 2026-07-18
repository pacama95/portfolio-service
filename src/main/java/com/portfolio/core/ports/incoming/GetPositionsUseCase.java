package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.Position;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.util.List;

public interface GetPositionsUseCase {

    Uni<Result> execute(Query query);

    sealed interface Result {
        record Success(List<Position> positions) implements Result {}
    }

    record Query(UserId userId, boolean activeOnly) {
        public static Query all(UserId userId) {
            return new Query(userId, false);
        }

        public static Query active(UserId userId) {
            return new Query(userId, true);
        }
    }
}

package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Position;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.math.BigDecimal;

public interface UpdateMarketPriceUseCase {

    Uni<Result> execute(Command command);

    sealed interface Result {
        record Success(Position position) implements Result {}
        record NotFound(String ticker) implements Result {}
        record InvalidRequest(String message) implements Result {}
    }

    record Command(UserId userId, String ticker, BigDecimal price, Currency currency) {
    }
}

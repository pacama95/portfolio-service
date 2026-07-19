package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface UpdateTransactionUseCase {

    Uni<Result> execute(Command command);

    sealed interface Result {
        record Success(Transaction transaction) implements Result {}
        record NotFound(UUID id) implements Result {}
        record InvalidRequest(String message) implements Result {}
        record Conflict(String message) implements Result {}
    }

    record Command(
            UserId userId,
            UUID id,
            String ticker,
            TransactionType transactionType,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal fees,
            Currency currency,
            LocalDate transactionDate,
            String notes,
            Boolean fractional,
            BigDecimal fractionalMultiplier,
            Currency commissionCurrency,
            String exchange,
            String country,
            String companyName
    ) {
    }
}

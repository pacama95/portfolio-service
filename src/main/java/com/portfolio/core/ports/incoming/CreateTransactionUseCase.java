package com.portfolio.core.ports.incoming;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import io.smallrye.mutiny.Uni;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface CreateTransactionUseCase {

    Uni<Result> execute(Command command);

    sealed interface Result {
        record Success(Transaction transaction) implements Result {}
        record InvalidRequest(String message) implements Result {}
    }

    record Command(
            UserId userId,
            String ticker,
            TransactionType transactionType,
            AssetType assetType,
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

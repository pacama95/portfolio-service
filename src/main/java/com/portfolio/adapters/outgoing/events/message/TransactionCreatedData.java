package com.portfolio.adapters.outgoing.events.message;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.TransactionType;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payload carried inside {@link EventMessage} for {@code transaction:created}. Field names mirror
 * the contract consumers were built against; {@code userId} is the one addition, kept so the
 * envelope carries everything the previous flat-field entry did.
 * <p>
 * Null-valued optional fields are omitted rather than serialized as {@code null}, matching the
 * previous behaviour where absent values simply had no stream field.
 * <p>
 * Dates are pinned to ISO-8601 strings for the same reason as {@link EventMessage}: without it
 * they serialize as numeric arrays whenever {@code WRITE_DATES_AS_TIMESTAMPS} is on, which
 * consumers cannot read.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionCreatedData(
        UUID id,
        String userId,
        String ticker,
        TransactionType transactionType,
        AssetType assetType,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fees,
        Currency currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate transactionDate,
        String notes,
        Boolean isFractional,
        BigDecimal fractionalMultiplier,
        Currency commissionCurrency,
        String exchange,
        String country,
        String companyName,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt
) {
}

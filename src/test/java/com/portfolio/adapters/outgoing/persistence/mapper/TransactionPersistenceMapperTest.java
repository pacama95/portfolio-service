package com.portfolio.adapters.outgoing.persistence.mapper;

import com.portfolio.adapters.outgoing.persistence.entity.TransactionEntity;
import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionPersistenceMapperTest {

    private TransactionPersistenceMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TransactionPersistenceMapperImpl();
    }

    @Test
    void givenNullTransaction_whenToEntity_thenReturnsNull() {
        assertNull(mapper.toEntity(null));
    }

    @Test
    void givenNullEntity_whenToDomain_thenReturnsNull() {
        assertNull(mapper.toDomain(null));
    }

    @Test
    void givenFullTransaction_whenRoundTripToEntityAndDomain_thenPreservesAllFields() {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2024-01-01T08:00:00Z");
        OffsetDateTime updatedAt = OffsetDateTime.parse("2024-01-02T09:00:00Z");
        Transaction original = new Transaction(
                id,
                UserId.of("user-round-trip"),
                "NVDA",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("2.5"),
                new BigDecimal("500.00"),
                new BigDecimal("4.25"),
                Currency.USD,
                LocalDate.of(2024, 5, 15),
                "round trip notes",
                true,
                new BigDecimal("0.00000003"),
                Currency.EUR,
                "NASDAQ",
                "US",
                "NVIDIA Corp.",
                createdAt,
                updatedAt);

        TransactionEntity entity = mapper.toEntity(original);
        Transaction restored = mapper.toDomain(entity);

        assertEquals(original.id(), restored.id());
        assertEquals(original.userId(), restored.userId());
        assertEquals(original.ticker(), restored.ticker());
        assertEquals(original.transactionType(), restored.transactionType());
        assertEquals(original.assetType(), restored.assetType());
        assertEquals(original.quantity(), restored.quantity());
        assertEquals(original.price(), restored.price());
        assertEquals(original.fees(), restored.fees());
        assertEquals(original.currency(), restored.currency());
        assertEquals(original.transactionDate(), restored.transactionDate());
        assertEquals(original.notes(), restored.notes());
        assertEquals(original.fractional(), restored.fractional());
        assertEquals(original.fractionalMultiplier(), restored.fractionalMultiplier());
        assertEquals(original.commissionCurrency(), restored.commissionCurrency());
        assertEquals(original.exchange(), restored.exchange());
        assertEquals(original.country(), restored.country());
        assertEquals(original.companyName(), restored.companyName());
        assertEquals(original.createdAt(), restored.createdAt());
        assertEquals(original.updatedAt(), restored.updatedAt());

        assertEquals("user-round-trip", entity.userId);
        assertEquals(TransactionEntity.TransactionTypeDb.BUY, entity.transactionType);
        assertEquals(TransactionEntity.AssetTypeDb.COMMON_STOCK, entity.assetType);
        assertEquals(TransactionEntity.CurrencyDb.USD, entity.currency);
        assertEquals(TransactionEntity.CurrencyDb.EUR, entity.commissionCurrency);
    }

    @Test
    void givenNullCommissionCurrency_whenRoundTripToEntityAndDomain_thenCommissionCurrencyRemainsNull() {
        Transaction original = new Transaction(
                UUID.randomUUID(),
                UserId.of("user-no-commission"),
                "AMZN",
                TransactionType.SELL,
                AssetType.ETF,
                BigDecimal.ONE,
                new BigDecimal("100"),
                BigDecimal.ZERO,
                Currency.GBP,
                LocalDate.of(2024, 7, 1),
                null,
                false,
                BigDecimal.ONE,
                null,
                "LSE",
                "GB",
                "Amazon.com Inc.",
                OffsetDateTime.parse("2024-07-01T12:00:00Z"),
                OffsetDateTime.parse("2024-07-01T12:00:00Z"));

        TransactionEntity entity = mapper.toEntity(original);
        Transaction restored = mapper.toDomain(entity);

        assertNull(entity.commissionCurrency);
        assertNull(restored.commissionCurrency());
    }

    @Test
    void givenNullInputs_whenNamedConversionMethods_thenReturnNull() {
        assertNull(mapper.userIdToString(null));
        assertNull(mapper.stringToUserId(null));
        assertNull(mapper.toTypeDb(null));
        assertNull(mapper.fromTypeDb(null));
        assertNull(mapper.toAssetDb(null));
        assertNull(mapper.fromAssetDb(null));
        assertNull(mapper.toCurrencyDb(null));
        assertNull(mapper.fromCurrencyDb(null));
    }

    @Test
    void givenNonNullInputs_whenNamedConversionMethods_thenConvertValues() {
        assertEquals("user-abc", mapper.userIdToString(UserId.of("user-abc")));
        assertEquals(UserId.of("user-abc"), mapper.stringToUserId("user-abc"));
        assertEquals(TransactionEntity.TransactionTypeDb.DIVIDEND, mapper.toTypeDb(TransactionType.DIVIDEND));
        assertEquals(TransactionType.DIVIDEND, mapper.fromTypeDb(TransactionEntity.TransactionTypeDb.DIVIDEND));
        assertEquals(TransactionEntity.AssetTypeDb.REIT, mapper.toAssetDb(AssetType.REIT));
        assertEquals(AssetType.REIT, mapper.fromAssetDb(TransactionEntity.AssetTypeDb.REIT));
        assertEquals(TransactionEntity.CurrencyDb.JPY, mapper.toCurrencyDb(Currency.JPY));
        assertEquals(Currency.JPY, mapper.fromCurrencyDb(TransactionEntity.CurrencyDb.JPY));
    }

    @Test
    void givenFractionalTransaction_whenRoundTripToEntityAndDomain_thenPreservesFractionalFlag() {
        Transaction original = new Transaction(
                UUID.randomUUID(),
                UserId.of("user-fractional"),
                "BRK.B",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("0.25"),
                new BigDecimal("400"),
                BigDecimal.ZERO,
                Currency.USD,
                LocalDate.of(2024, 8, 1),
                null,
                true,
                new BigDecimal("0.00000001"),
                Currency.USD,
                null,
                null,
                null,
                OffsetDateTime.parse("2024-08-01T10:00:00Z"),
                OffsetDateTime.parse("2024-08-01T10:00:00Z"));

        Transaction restored = mapper.toDomain(mapper.toEntity(original));

        assertTrue(restored.fractional());
        assertEquals(new BigDecimal("0.00000001"), restored.fractionalMultiplier());
    }
}

package com.portfolio.adapters.outgoing.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    public UUID id;

    @Column(name = "user_id", nullable = false, length = 128)
    public String userId;

    @Column(nullable = false, length = 20)
    public String ticker;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "transaction_type", nullable = false, columnDefinition = "transaction_type")
    public TransactionTypeDb transactionType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "asset_type", nullable = false, columnDefinition = "asset_type")
    public AssetTypeDb assetType;

    @Column(nullable = false, precision = 18, scale = 6)
    public BigDecimal quantity;

    @Column(nullable = false, precision = 18, scale = 6)
    public BigDecimal price;

    @Column(nullable = false, precision = 18, scale = 6)
    public BigDecimal fees;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "currency_type")
    public CurrencyDb currency;

    @Column(name = "transaction_date", nullable = false)
    public LocalDate transactionDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "commission_currency", columnDefinition = "currency_type")
    public CurrencyDb commissionCurrency;

    @Column(length = 20, nullable = false)
    public String exchange;

    @Column(length = 50, nullable = false)
    public String country;

    @Column(name = "company_name")
    public String companyName;

    @Column(name = "is_fractional", nullable = false)
    public boolean fractional;

    @Column(name = "fractional_multiplier", nullable = false, precision = 10, scale = 8)
    public BigDecimal fractionalMultiplier;

    @Column
    public String notes;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    public enum TransactionTypeDb { BUY, SELL, DIVIDEND, SPLIT }

    public enum CurrencyDb { USD, EUR, GBP, CAD, JPY, CHF, AUD, HKD, SEK, NOK }

    public enum AssetTypeDb {
        AMERICAN_DEPOSITARY_RECEIPT,
        BOND,
        BOND_FUND,
        CLOSED_END_FUND,
        COMMON_STOCK,
        DEPOSITARY_RECEIPT,
        DIGITAL_CURRENCY,
        ETF,
        EXCHANGE_TRADED_NOTE,
        GLOBAL_DEPOSITARY_RECEIPT,
        LIMITED_PARTNERSHIP,
        MUTUAL_FUND,
        PHYSICAL_CURRENCY,
        PREFERRED_STOCK,
        REIT,
        RIGHT,
        STRUCTURED_PRODUCT,
        TRUST,
        UNIT,
        WARRANT
    }
}

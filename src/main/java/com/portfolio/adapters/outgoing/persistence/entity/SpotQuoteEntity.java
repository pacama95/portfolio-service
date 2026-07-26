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
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "spot_quotes")
public class SpotQuoteEntity {

    @Id
    public UUID id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "spot_quote_kind")
    public SpotQuoteKindDb kind;

    @Column(nullable = false, length = 32)
    public String symbol;

    @Column(nullable = false, precision = 18, scale = 8)
    public BigDecimal value;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "currency_type")
    public TransactionEntity.CurrencyDb currency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "base_currency", columnDefinition = "currency_type")
    public TransactionEntity.CurrencyDb baseCurrency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "quote_currency", columnDefinition = "currency_type")
    public TransactionEntity.CurrencyDb quoteCurrency;

    @Column(name = "as_of", nullable = false)
    public OffsetDateTime asOf;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "quote_source")
    public QuoteSourceDb source;

    @Column(length = 64)
    public String provider;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    public enum SpotQuoteKindDb { PRICE, FX }

    public enum QuoteSourceDb { PROVIDER, MANUAL }
}

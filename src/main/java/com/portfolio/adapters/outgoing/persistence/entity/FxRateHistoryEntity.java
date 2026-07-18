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
@Table(name = "fx_rate_history")
public class FxRateHistoryEntity {

    @Id
    public UUID id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "base_currency", nullable = false, columnDefinition = "currency_type")
    public TransactionEntity.CurrencyDb baseCurrency;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "quote_currency", nullable = false, columnDefinition = "currency_type")
    public TransactionEntity.CurrencyDb quoteCurrency;

    @Column(name = "rate_date", nullable = false)
    public LocalDate rateDate;

    @Column(nullable = false, precision = 18, scale = 8)
    public BigDecimal rate;

    @Column(length = 64)
    public String provider;

    @Column(name = "fetched_at", nullable = false)
    public OffsetDateTime fetchedAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}

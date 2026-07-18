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
@Table(name = "price_history")
public class PriceHistoryEntity {

    @Id
    public UUID id;

    @Column(nullable = false, length = 32)
    public String symbol;

    @Column(name = "price_date", nullable = false)
    public LocalDate priceDate;

    @Column(name = "close_price", nullable = false, precision = 18, scale = 6)
    public BigDecimal closePrice;

    @Column(name = "adjusted_close_price", precision = 18, scale = 6)
    public BigDecimal adjustedClosePrice;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "currency_type")
    public TransactionEntity.CurrencyDb currency;

    @Column(length = 64)
    public String provider;

    @Column(name = "fetched_at", nullable = false)
    public OffsetDateTime fetchedAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}

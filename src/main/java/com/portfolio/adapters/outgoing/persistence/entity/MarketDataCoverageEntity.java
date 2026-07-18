package com.portfolio.adapters.outgoing.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "market_data_coverage")
public class MarketDataCoverageEntity {

    @Id
    public UUID id;

    @Column(name = "coverage_kind", nullable = false, length = 16)
    public String coverageKind;

    @Column(nullable = false, length = 64)
    public String symbol;

    @Column(name = "from_date", nullable = false)
    public LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    public LocalDate toDate;

    @Column(length = 64)
    public String provider;

    @Column(name = "fetched_at", nullable = false)
    public OffsetDateTime fetchedAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;
}

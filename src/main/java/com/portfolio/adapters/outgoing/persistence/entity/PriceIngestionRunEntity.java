package com.portfolio.adapters.outgoing.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "price_ingestion_runs")
public class PriceIngestionRunEntity {

    @Id
    public UUID id;

    @Column(name = "user_id", length = 128)
    public String userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "ingestion_run_status")
    public StatusDb status;

    @Column(name = "from_date")
    public LocalDate fromDate;

    @Column(name = "to_date")
    public LocalDate toDate;

    @Column(length = 32)
    public String ticker;

    @Column(name = "include_backfill", nullable = false)
    public boolean includeBackfill;

    @Column(name = "symbols_requested", nullable = false)
    public int symbolsRequested;

    @Column(name = "symbols_completed", nullable = false)
    public int symbolsCompleted;

    @Column(name = "error_message")
    public String errorMessage;

    @Column(name = "started_at")
    public OffsetDateTime startedAt;

    @Column(name = "completed_at")
    public OffsetDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    public enum StatusDb { PENDING, RUNNING, COMPLETED, PARTIAL, FAILED }
}

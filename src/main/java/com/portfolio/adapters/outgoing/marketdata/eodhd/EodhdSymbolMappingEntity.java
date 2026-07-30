package com.portfolio.adapters.outgoing.marketdata.eodhd;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "eodhd_symbol_mappings")
class EodhdSymbolMappingEntity {

    @Id
    @Column(name = "canonical_symbol", length = 32)
    String canonicalSymbol;

    @Column(name = "provider_symbol", nullable = false, length = 64)
    String providerSymbol;

    @Column(name = "exchange_code", nullable = false, length = 20)
    String exchangeCode;

    @Column(name = "raw_currency", length = 10)
    String rawCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_source", nullable = false, length = 32)
    EodhdResolutionSource resolutionSource;

    @Column(name = "resolved_at", nullable = false)
    OffsetDateTime resolvedAt;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

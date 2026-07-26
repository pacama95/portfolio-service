package com.portfolio.adapters.outgoing.marketdata.eodhd;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "eodhd_exchanges")
class EodhdExchangeEntity {

    @Id
    @Column(length = 20)
    String code;

    @Column(nullable = false)
    String name;

    @Column(name = "operating_mic")
    String operatingMic;

    @Column(length = 100)
    String country;

    @Column(length = 10)
    String currency;

    @Column(name = "country_iso2", length = 2)
    String countryIso2;

    @Column(name = "country_iso3", length = 3)
    String countryIso3;

    @Column(nullable = false)
    boolean active;

    @Column(name = "fetched_at", nullable = false)
    OffsetDateTime fetchedAt;

    @Column(name = "updated_at", nullable = false)
    OffsetDateTime updatedAt;
}

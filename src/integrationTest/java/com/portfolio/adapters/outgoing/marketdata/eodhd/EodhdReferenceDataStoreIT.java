package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.integration.IntegrationTestProfile;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class EodhdReferenceDataStoreIT {

    private static final OffsetDateTime FIRST_FETCH = OffsetDateTime.parse("2024-01-01T12:00:00Z");
    private static final OffsetDateTime SECOND_FETCH = OffsetDateTime.parse("2024-01-02T12:00:00Z");

    @Inject
    EodhdReferenceDataStore store;

    @SuppressWarnings("unused")
    private final ConnectionHolder connectionHolder =
            () -> CDI.current().select(AgroalDataSource.class).get().getConnection();

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true)
    void givenSnapshots_whenReplaced_thenMissingExchangesBecomeInactive(UniAsserter asserter) {
        asserter.execute(() -> store.replaceExchanges(
                List.of(exchange("US", "US Exchanges", "XNAS,XNYS", "USA", "USD", FIRST_FETCH),
                        exchange("LSE", "London Exchange", "XLON", "UK", "GBP", FIRST_FETCH)),
                FIRST_FETCH));
        asserter.assertThat(
                store::findLatestExchangeFetchTime,
                latest -> assertEquals(FIRST_FETCH, latest.orElseThrow()));

        asserter.execute(() -> store.replaceExchanges(
                List.of(exchange("US", "US Exchanges", "XNAS,XNYS", "USA", "USD", SECOND_FETCH)),
                SECOND_FETCH));
        asserter.assertThat(
                store::findExchanges,
                exchanges -> {
                    assertEquals(2, exchanges.size());
                    assertTrue(exchanges.stream().filter(exchange -> exchange.code().equals("US"))
                            .findFirst().orElseThrow().active());
                    assertFalse(exchanges.stream().filter(exchange -> exchange.code().equals("LSE"))
                            .findFirst().orElseThrow().active());
                });
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true)
    void givenResolvedSymbol_whenUpserted_thenMappingCanBeReused(UniAsserter asserter) {
        EodhdSymbolMapping mapping = new EodhdSymbolMapping(
                "AAPL",
                "AAPL.US",
                "US",
                "USD",
                "fingerprint",
                EodhdResolutionSource.TRANSACTION_METADATA,
                FIRST_FETCH);

        asserter.execute(() -> store.replaceExchanges(
                List.of(exchange("US", "US Exchanges", "XNAS,XNYS", "USA", "USD", FIRST_FETCH)),
                FIRST_FETCH));
        asserter.execute(() -> store.upsertSymbolMapping(mapping));
        asserter.assertThat(
                () -> store.findSymbolMapping(" aapl "),
                found -> assertEquals(mapping, found.orElseThrow()));
    }

    private EodhdExchange exchange(
            String code,
            String name,
            String mic,
            String country,
            String currency,
            OffsetDateTime fetchedAt) {
        return new EodhdExchange(code, name, mic, country, currency, null, null, true, fetchedAt);
    }
}

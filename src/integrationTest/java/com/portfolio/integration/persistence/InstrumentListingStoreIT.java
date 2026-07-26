package com.portfolio.integration.persistence;

import com.github.database.rider.core.api.configuration.DBUnit;
import com.github.database.rider.core.api.connection.ConnectionHolder;
import com.github.database.rider.core.api.dataset.DataSet;
import com.github.database.rider.junit5.api.DBRider;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.InstrumentListing;
import com.portfolio.core.ports.outgoing.InstrumentListingPort;
import com.portfolio.integration.IntegrationTestProfile;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DBRider
@DBUnit(schema = "public", caseSensitiveTableNames = true, cacheConnection = false)
class InstrumentListingStoreIT {

    @Inject
    InstrumentListingPort listings;

    @SuppressWarnings("unused")
    private final ConnectionHolder connectionHolder =
            () -> CDI.current().select(AgroalDataSource.class).get().getConnection();

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenTransactions_whenFindBySymbol_thenReturnsDistinctListingMetadata(UniAsserter asserter) {
        asserter.assertThat(
                () -> listings.findBySymbol(" aapl "),
                result -> assertEquals(
                        List.of(new InstrumentListing("AAPL", "NASDAQ", "US", Currency.USD)),
                        result));
    }

    @Test
    @RunOnVertxContext
    @DataSet(cleanBefore = true, executeScriptsBefore = "datasets/transactions-seed.sql")
    void givenUnknownSymbol_whenFindBySymbol_thenReturnsEmptyList(UniAsserter asserter) {
        asserter.assertThat(() -> listings.findBySymbol("UNKNOWN"), result -> assertTrue(result.isEmpty()));
    }
}

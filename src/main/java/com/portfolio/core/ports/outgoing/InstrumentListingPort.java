package com.portfolio.core.ports.outgoing;

import com.portfolio.core.model.InstrumentListing;
import io.smallrye.mutiny.Uni;

import java.util.List;

/**
 * Supplies listing hints without exposing a market-data provider's symbol convention.
 */
public interface InstrumentListingPort {

    Uni<List<InstrumentListing>> findBySymbol(String symbol);
}

package com.portfolio.core.ports.outgoing;

import io.smallrye.mutiny.Uni;

/**
 * Discards any cached provider-specific translation of a canonical symbol.
 *
 * <p>Providers resolve a bare ticker to their own identifier using the listing metadata recorded
 * on transactions. Changing that metadata is therefore what makes a translation wrong, so the
 * transaction write invalidates it rather than every later read re-checking it.
 */
public interface ProviderSymbolMappingPort {

    Uni<Void> invalidate(String canonicalSymbol);
}

package com.portfolio.core.ports.outgoing;

import com.portfolio.core.model.IngestionRun;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface PriceIngestionRunRepository {

    Uni<Boolean> hasActiveRun(Duration staleAfter);

    Uni<IngestionRun> save(IngestionRun run);

    Uni<Optional<IngestionRun>> findById(UUID id);

    Uni<Optional<IngestionRun>> findLatest();
}

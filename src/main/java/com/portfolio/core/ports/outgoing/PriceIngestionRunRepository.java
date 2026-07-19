package com.portfolio.core.ports.outgoing;

import com.portfolio.core.model.IngestionRun;
import io.smallrye.mutiny.Uni;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface PriceIngestionRunRepository {

    Uni<Optional<IngestionRun>> startRunIfNoneActive(Duration staleAfter, IngestionRun candidate);

    Uni<IngestionRun> save(IngestionRun run);

    Uni<Optional<IngestionRun>> findById(UUID id);

    Uni<Optional<IngestionRun>> findLatest();
}

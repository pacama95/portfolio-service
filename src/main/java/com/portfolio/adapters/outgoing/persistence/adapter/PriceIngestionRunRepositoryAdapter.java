package com.portfolio.adapters.outgoing.persistence.adapter;

import com.portfolio.adapters.outgoing.persistence.entity.PriceIngestionRunEntity;
import com.portfolio.core.model.IngestionRun;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.outgoing.PriceIngestionRunRepository;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PriceIngestionRunRepositoryAdapter implements PriceIngestionRunRepository {

    private static final List<PriceIngestionRunEntity.StatusDb> ACTIVE_STATUSES = List.of(
            PriceIngestionRunEntity.StatusDb.PENDING,
            PriceIngestionRunEntity.StatusDb.RUNNING);

    // Arbitrary fixed key for a single global advisory lock serializing "is a run already
    // active" check-then-act sequences — there is only ever one ingestion-trigger slot, unlike
    // the per-(userId, ticker) locks used for transaction writes.
    private static final long INGESTION_TRIGGER_LOCK_KEY = 0x494e4753_54524947L;

    @Override
    @WithTransaction
    public Uni<Optional<IngestionRun>> startRunIfNoneActive(Duration staleAfter, IngestionRun candidate) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minus(staleAfter);
        return Panache.getSession().flatMap(session ->
                session.createNativeQuery("select pg_advisory_xact_lock(:key)", Long.class)
                        .setParameter("key", INGESTION_TRIGGER_LOCK_KEY)
                        .getSingleResultOrNull()
                        .chain(ignored -> session.createQuery(
                                        "update PriceIngestionRunEntity r set r.status = :failed, "
                                                + "r.errorMessage = :error, r.completedAt = :now, "
                                                + "r.updatedAt = :now "
                                                + "where r.status in (:active) "
                                                + "and coalesce(r.startedAt, r.createdAt) < :cutoff")
                                .setParameter("failed", PriceIngestionRunEntity.StatusDb.FAILED)
                                .setParameter("error", "Ingestion run timed out")
                                .setParameter("now", now)
                                .setParameter("active", ACTIVE_STATUSES)
                                .setParameter("cutoff", cutoff)
                                .executeUpdate())
                        .chain(() -> session.createQuery(
                                        "select count(r) from PriceIngestionRunEntity r where r.status in (:active)",
                                        Long.class)
                                .setParameter("active", ACTIVE_STATUSES)
                                .getSingleResult())
                        .flatMap(count -> {
                            if (count != null && count > 0) {
                                return Uni.createFrom().<Optional<IngestionRun>>item(Optional.empty());
                            }
                            return session.merge(toEntity(candidate)).map(this::toDomain).map(Optional::of);
                        }));
    }

    @Override
    @WithTransaction
    public Uni<IngestionRun> save(IngestionRun run) {
        return Panache.getSession().flatMap(session ->
                session.merge(toEntity(run)).map(this::toDomain));
    }

    @Override
    @WithSession
    public Uni<Optional<IngestionRun>> findById(UUID id) {
        return Panache.getSession().flatMap(session ->
                session.find(PriceIngestionRunEntity.class, id))
                .map(entity -> Optional.ofNullable(entity).map(this::toDomain));
    }

    @Override
    @WithSession
    public Uni<Optional<IngestionRun>> findLatest() {
        return Panache.getSession().flatMap(session ->
                session.createQuery(
                                "from PriceIngestionRunEntity r order by r.createdAt desc",
                                PriceIngestionRunEntity.class)
                        .setMaxResults(1)
                        .getSingleResultOrNull())
                .map(entity -> Optional.ofNullable(entity).map(this::toDomain));
    }

    private PriceIngestionRunEntity toEntity(IngestionRun run) {
        PriceIngestionRunEntity entity = new PriceIngestionRunEntity();
        entity.id = run.id();
        entity.userId = run.userId() != null ? run.userId().value() : null;
        entity.status = PriceIngestionRunEntity.StatusDb.valueOf(run.status().name());
        entity.fromDate = run.fromDate();
        entity.toDate = run.toDate();
        entity.ticker = run.ticker();
        entity.includeBackfill = run.includeBackfill();
        entity.symbolsRequested = run.symbolsRequested();
        entity.symbolsCompleted = run.symbolsCompleted();
        entity.errorMessage = run.errorMessage();
        entity.startedAt = run.startedAt();
        entity.completedAt = run.completedAt();
        entity.createdAt = run.createdAt();
        entity.updatedAt = run.updatedAt();
        return entity;
    }

    private IngestionRun toDomain(PriceIngestionRunEntity entity) {
        return new IngestionRun(
                entity.id,
                entity.userId != null ? UserId.of(entity.userId) : null,
                IngestionRun.Status.valueOf(entity.status.name()),
                entity.fromDate,
                entity.toDate,
                entity.ticker,
                entity.includeBackfill,
                entity.symbolsRequested,
                entity.symbolsCompleted,
                entity.errorMessage,
                entity.startedAt,
                entity.completedAt,
                entity.createdAt,
                entity.updatedAt);
    }
}

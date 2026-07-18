package com.portfolio.adapters.outgoing.persistence.adapter;

import com.portfolio.adapters.outgoing.persistence.entity.TransactionEntity;
import com.portfolio.adapters.outgoing.persistence.mapper.TransactionPersistenceMapper;
import com.portfolio.adapters.outgoing.persistence.repository.TransactionPanacheRepository;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionSortOrder;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class TransactionRepositoryAdapter implements TransactionRepository {

    private final TransactionPanacheRepository repository;
    private final TransactionPersistenceMapper mapper;

    public TransactionRepositoryAdapter(
            TransactionPanacheRepository repository,
            TransactionPersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @WithTransaction
    public Uni<Transaction> save(Transaction transaction) {
        TransactionEntity entity = mapper.toEntity(transaction);
        OffsetDateTime now = OffsetDateTime.now();
        if (entity.createdAt == null) {
            entity.createdAt = now;
        }
        entity.updatedAt = now;
        return repository.getSession().flatMap(session ->
                session.merge(entity).map(mapper::toDomain));
    }

    @Override
    @WithSession
    public Uni<Optional<Transaction>> findById(UserId userId, UUID id) {
        return repository.findByUserAndId(userId.value(), id)
                .map(entity -> Optional.ofNullable(entity).map(mapper::toDomain));
    }

    @Override
    @WithSession
    public Uni<List<Transaction>> findAll(UserId userId) {
        return repository.findAllByUser(userId.value())
                .map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    @WithSession
    public Uni<List<Transaction>> findByTicker(UserId userId, String ticker) {
        return repository.findByUserAndTicker(userId.value(), ticker)
                .map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    @WithSession
    public Uni<List<Transaction>> search(
            UserId userId,
            String ticker,
            TransactionType type,
            LocalDate fromDate,
            LocalDate toDate,
            TransactionSortOrder sortOrder) {
        TransactionEntity.TransactionTypeDb typeDb = type == null
                ? null
                : TransactionEntity.TransactionTypeDb.valueOf(type.name());
        boolean ascending = sortOrder == TransactionSortOrder.ASC;
        return repository.search(userId.value(), ticker, typeDb, fromDate, toDate, ascending)
                .map(list -> list.stream().map(mapper::toDomain).toList());
    }

    @Override
    @WithTransaction
    public Uni<Boolean> deleteById(UserId userId, UUID id) {
        return repository.deleteByUserAndId(userId.value(), id);
    }

    @Override
    @WithSession
    public Uni<Long> count(UserId userId) {
        return repository.countByUser(userId.value());
    }

    @Override
    @WithSession
    public Uni<Long> countByTicker(UserId userId, String ticker) {
        return repository.countByUserAndTicker(userId.value(), ticker);
    }

    @Override
    @WithSession
    public Uni<List<String>> findDistinctTickers() {
        return repository.findDistinctTickers();
    }
}

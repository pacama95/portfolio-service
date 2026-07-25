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
import org.hibernate.reactive.mutiny.Mutiny;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

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
    public Uni<List<Transaction>> findAll() {
        return repository.listAll().map(list -> list.stream().map(mapper::toDomain).toList());
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
            TransactionSortOrder sortOrder,
            Integer limit,
            Integer offset) {
        TransactionEntity.TransactionTypeDb typeDb = type == null
                ? null
                : TransactionEntity.TransactionTypeDb.valueOf(type.name());
        boolean ascending = sortOrder == TransactionSortOrder.ASC;
        return repository.search(userId.value(), ticker, typeDb, fromDate, toDate, ascending, limit, offset)
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
    @WithTransaction
    public <T> Uni<T> withTickersLocked(
            UserId userId, List<String> tickers, Function<Map<String, List<Transaction>>, Uni<T>> action) {
        // Sorted so two calls locking the same ticker pair in opposite order (e.g. a ticker
        // rename crossing another rename the other way) always acquire locks in the same
        // sequence, and can never deadlock against each other.
        List<String> sorted = new ArrayList<>(new TreeSet<>(tickers));
        return repository.getSession()
                .flatMap(session -> acquireLocks(session, userId.value(), sorted))
                .flatMap(ignored -> loadByTicker(userId, sorted))
                .flatMap(action::apply);
    }

    private Uni<Void> acquireLocks(Mutiny.Session session, String userId, List<String> tickers) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (String ticker : tickers) {
            chain = chain.chain(() -> session.createNativeQuery("select pg_advisory_xact_lock(:key)", Long.class)
                    .setParameter("key", lockKey(userId, ticker))
                    .getSingleResultOrNull()
                    .replaceWithVoid());
        }
        return chain;
    }

    private Uni<Map<String, List<Transaction>>> loadByTicker(UserId userId, List<String> tickers) {
        Uni<Map<String, List<Transaction>>> chain = Uni.createFrom().item(new HashMap<>());
        for (String ticker : tickers) {
            chain = chain.flatMap(map -> repository.findByUserAndTicker(userId.value(), ticker)
                    .map(entities -> {
                        map.put(ticker, entities.stream().map(mapper::toDomain).toList());
                        return map;
                    }));
        }
        return chain;
    }

    /**
     * Combines the two 32-bit hashes into a 64-bit advisory lock key. A hash collision only
     * makes two unrelated (userId, ticker) pairs share a lock — extra, harmless serialization,
     * never a missed one.
     */
    private static long lockKey(String userId, String ticker) {
        return ((long) userId.hashCode() << 32) ^ (ticker.hashCode() & 0xffffffffL);
    }
}

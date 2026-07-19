package com.portfolio.adapters.outgoing.persistence.repository;

import com.portfolio.adapters.outgoing.persistence.entity.TransactionEntity;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class TransactionPanacheRepository implements PanacheRepositoryBase<TransactionEntity, UUID> {

    public Uni<List<TransactionEntity>> findAllByUser(String userId) {
        return list("userId", Sort.by("transactionDate").descending().and("createdAt").descending(), userId);
    }

    public Uni<List<TransactionEntity>> findByUserAndTicker(String userId, String ticker) {
        return list("userId = ?1 and ticker = ?2",
                Sort.by("transactionDate").descending(),
                userId, ticker);
    }

    public Uni<TransactionEntity> findByUserAndId(String userId, UUID id) {
        return find("userId = ?1 and id = ?2", userId, id).firstResult();
    }

    public Uni<List<TransactionEntity>> search(
            String userId,
            String ticker,
            TransactionEntity.TransactionTypeDb type,
            LocalDate fromDate,
            LocalDate toDate,
            boolean ascending) {
        StringBuilder query = new StringBuilder("userId = :userId");
        Parameters parameters = Parameters.with("userId", userId);

        if (ticker != null && !ticker.isBlank()) {
            query.append(" and ticker = :ticker");
            parameters = parameters.and("ticker", ticker);
        }
        if (type != null) {
            query.append(" and transactionType = :type");
            parameters = parameters.and("type", type);
        }
        if (fromDate != null) {
            query.append(" and transactionDate >= :fromDate");
            parameters = parameters.and("fromDate", fromDate);
        }
        if (toDate != null) {
            query.append(" and transactionDate <= :toDate");
            parameters = parameters.and("toDate", toDate);
        }

        Sort sort = ascending
                ? Sort.by("transactionDate").ascending().and("createdAt").ascending()
                : Sort.by("transactionDate").descending().and("createdAt").descending();

        return find(query.toString(), sort, parameters).list();
    }

    public Uni<Long> countByUser(String userId) {
        return count("userId", userId);
    }

    public Uni<Long> countByUserAndTicker(String userId, String ticker) {
        return count("userId = ?1 and ticker = ?2", userId, ticker);
    }

    public Uni<Boolean> deleteByUserAndId(String userId, UUID id) {
        return delete("userId = ?1 and id = ?2", userId, id).map(count -> count > 0);
    }

}

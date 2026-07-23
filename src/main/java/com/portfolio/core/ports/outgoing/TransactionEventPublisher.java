package com.portfolio.core.ports.outgoing;

import com.portfolio.core.model.event.TransactionCreatedEvent;
import io.smallrye.mutiny.Uni;

public interface TransactionEventPublisher {

    Uni<Void> publishTransactionCreated(TransactionCreatedEvent event);
}

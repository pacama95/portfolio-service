package com.portfolio.adapters.common;

import com.portfolio.adapters.incoming.rest.PriceIngestionController;
import com.portfolio.adapters.incoming.rest.dto.CreateTransactionRequest;
import com.portfolio.adapters.incoming.rest.dto.PortfolioSummaryResponse;
import com.portfolio.adapters.incoming.rest.dto.PositionResponse;
import com.portfolio.adapters.incoming.rest.dto.TransactionResponse;
import com.portfolio.adapters.incoming.rest.dto.UpdateMarketDataRequest;
import com.portfolio.adapters.incoming.rest.dto.UpdateTransactionRequest;
import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.CapitalFlowEntry;
import com.portfolio.core.model.CapitalFlowKind;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.DailyPositionSnapshot;
import com.portfolio.core.model.DailyValuation;
import com.portfolio.core.model.Exchange;
import com.portfolio.core.model.FxRateEntry;
import com.portfolio.core.model.IngestionRun;
import com.portfolio.core.model.PerformanceInputs;
import com.portfolio.core.model.PortfolioSummary;
import com.portfolio.core.model.Position;
import com.portfolio.core.model.PriceHistoryEntry;
import com.portfolio.core.model.QuoteSource;
import com.portfolio.core.model.SpotQuote;
import com.portfolio.core.model.SpotQuoteKind;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionSortOrder;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPriceIngestionRunUseCase;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Central native-image reflection registration for Jackson serialization.
 * Individual types are also annotated; this covers nested / port types returned on the wire.
 */
@RegisterForReflection(targets = {
        // REST DTOs
        CreateTransactionRequest.class,
        UpdateTransactionRequest.class,
        TransactionResponse.class,
        PositionResponse.class,
        PortfolioSummaryResponse.class,
        UpdateMarketDataRequest.class,
        PriceIngestionController.TriggerPriceIngestionRequest.class,
        // Domain models serialized in daily-history / positions responses
        Transaction.class,
        Position.class,
        PortfolioSummary.class,
        DailyPositionSnapshot.class,
        DailyValuation.class,
        CapitalFlowEntry.class,
        CapitalFlowKind.class,
        PriceHistoryEntry.class,
        FxRateEntry.class,
        SpotQuote.class,
        SpotQuoteKind.class,
        QuoteSource.class,
        PerformanceInputs.class,
        Exchange.class,
        IngestionRun.class,
        IngestionRun.Status.class,
        UserId.class,
        TransactionType.class,
        TransactionSortOrder.class,
        AssetType.class,
        Currency.class,
        // Port nested types returned as JSON
        GetPriceIngestionRunUseCase.RunStatus.class
})
public final class NativeReflectionConfig {

    private NativeReflectionConfig() {
    }
}

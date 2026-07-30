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
import com.portfolio.core.ports.incoming.CreateTransactionUseCase;
import com.portfolio.core.ports.incoming.DeleteTransactionUseCase;
import com.portfolio.core.ports.incoming.GetCapitalFlowsUseCase;
import com.portfolio.core.ports.incoming.GetDailyPositionHistoryUseCase;
import com.portfolio.core.ports.incoming.GetPerformanceInputsUseCase;
import com.portfolio.core.ports.incoming.GetPortfolioSummaryUseCase;
import com.portfolio.core.ports.incoming.GetPortfolioValuationHistoryUseCase;
import com.portfolio.core.ports.incoming.GetPositionsUseCase;
import com.portfolio.core.ports.incoming.GetPriceHistoryUseCase;
import com.portfolio.core.ports.incoming.GetPriceIngestionRunUseCase;
import com.portfolio.core.ports.incoming.SearchTransactionsUseCase;
import com.portfolio.core.ports.incoming.TriggerPriceIngestionUseCase;
import com.portfolio.core.ports.incoming.UpdateMarketPriceUseCase;
import com.portfolio.core.ports.incoming.UpdateTransactionUseCase;
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
        IngestionRun.class,
        IngestionRun.Status.class,
        UserId.class,
        TransactionType.class,
        TransactionSortOrder.class,
        AssetType.class,
        Currency.class,
        // Port nested types returned as JSON
        GetPriceIngestionRunUseCase.RunStatus.class,
        // Port Result.InvalidRequest / Result.Conflict variants: every controller method
        // returns the generic jakarta.ws.rs.core.Response, so Quarkus's build-time JAX-RS
        // scanner never sees which concrete record ends up behind Response.entity(...) inside
        // the result switch, and cannot auto-register it for reflection.
        CreateTransactionUseCase.Result.InvalidRequest.class,
        CreateTransactionUseCase.Result.Conflict.class,
        UpdateTransactionUseCase.Result.InvalidRequest.class,
        UpdateTransactionUseCase.Result.Conflict.class,
        DeleteTransactionUseCase.Result.Conflict.class,
        SearchTransactionsUseCase.Result.InvalidRequest.class,
        GetPositionsUseCase.Result.Conflict.class,
        UpdateMarketPriceUseCase.Result.InvalidRequest.class,
        GetPortfolioSummaryUseCase.Result.Conflict.class,
        GetDailyPositionHistoryUseCase.Result.InvalidRequest.class,
        GetDailyPositionHistoryUseCase.Result.Conflict.class,
        GetPriceHistoryUseCase.Result.InvalidRequest.class,
        GetPortfolioValuationHistoryUseCase.Result.InvalidRequest.class,
        GetPortfolioValuationHistoryUseCase.Result.Conflict.class,
        GetCapitalFlowsUseCase.Result.InvalidRequest.class,
        GetPerformanceInputsUseCase.Result.InvalidRequest.class,
        GetPerformanceInputsUseCase.Result.Conflict.class,
        TriggerPriceIngestionUseCase.Result.Conflict.class,
        TriggerPriceIngestionUseCase.Result.InvalidRequest.class
})
public final class NativeReflectionConfig {

    private NativeReflectionConfig() {
    }
}

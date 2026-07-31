package com.portfolio.adapters.incoming.mcp;

import com.portfolio.adapters.incoming.mcp.dto.DeleteConfirmationDto;
import com.portfolio.adapters.incoming.mcp.dto.TransactionCountDto;
import com.portfolio.adapters.incoming.mcp.dto.TransactionDto;
import com.portfolio.adapters.incoming.mcp.dto.TransactionListDto;
import com.portfolio.adapters.incoming.mcp.mapper.TransactionMcpMapper;
import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.TransactionSortOrder;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.ports.incoming.CountTransactionsUseCase;
import com.portfolio.core.ports.incoming.CreateTransactionUseCase;
import com.portfolio.core.ports.incoming.DeleteTransactionUseCase;
import com.portfolio.core.ports.incoming.GetTransactionUseCase;
import com.portfolio.core.ports.incoming.SearchTransactionsUseCase;
import com.portfolio.core.ports.incoming.UpdateTransactionUseCase;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@ApplicationScoped
class TransactionMcpTools {

    private final CreateTransactionUseCase createTransactionUseCase;
    private final UpdateTransactionUseCase updateTransactionUseCase;
    private final DeleteTransactionUseCase deleteTransactionUseCase;
    private final GetTransactionUseCase getTransactionUseCase;
    private final SearchTransactionsUseCase searchTransactionsUseCase;
    private final CountTransactionsUseCase countTransactionsUseCase;
    private final TransactionMcpMapper mapper;
    private final McpUserIdentity identity;
    private final McpFailureTranslator failureTranslator;

    TransactionMcpTools(
            CreateTransactionUseCase createTransactionUseCase,
            UpdateTransactionUseCase updateTransactionUseCase,
            DeleteTransactionUseCase deleteTransactionUseCase,
            GetTransactionUseCase getTransactionUseCase,
            SearchTransactionsUseCase searchTransactionsUseCase,
            CountTransactionsUseCase countTransactionsUseCase,
            TransactionMcpMapper mapper,
            McpUserIdentity identity,
            McpFailureTranslator failureTranslator) {
        this.createTransactionUseCase = createTransactionUseCase;
        this.updateTransactionUseCase = updateTransactionUseCase;
        this.deleteTransactionUseCase = deleteTransactionUseCase;
        this.getTransactionUseCase = getTransactionUseCase;
        this.searchTransactionsUseCase = searchTransactionsUseCase;
        this.countTransactionsUseCase = countTransactionsUseCase;
        this.mapper = mapper;
        this.identity = identity;
        this.failureTranslator = failureTranslator;
    }

    @Tool(name = "create_transaction", description = "Records a new transaction in the caller's ledger. "
            + "Positions and portfolio summaries are derived on read from the ledger, so this is how "
            + "new holdings, sales, dividends, or splits enter the system.", structuredContent = true)
    Uni<TransactionDto> createTransaction(
            @ToolArg(description = "Ticker symbol, e.g. AAPL") @NotBlank @Size(max = 20) String ticker,
            @ToolArg(description = "BUY, SELL, DIVIDEND, or SPLIT") @NotNull TransactionType transactionType,
            @ToolArg(description = "Instrument type, e.g. COMMON_STOCK, ETF, BOND") @NotNull AssetType assetType,
            @ToolArg(description = "Number of shares/units traded; must be at least 0.000001") @NotNull @DecimalMin("0.000001") BigDecimal quantity,
            @ToolArg(description = "Price per share/unit in the given currency; must be zero or positive") @NotNull @DecimalMin("0.0") BigDecimal price,
            @ToolArg(description = "Commission/fees paid, if any", required = false) BigDecimal fees,
            @ToolArg(description = "ISO currency code of the price, e.g. USD") @NotNull Currency currency,
            @ToolArg(description = "Date the transaction occurred (ISO-8601, YYYY-MM-DD)") @NotNull LocalDate transactionDate,
            @ToolArg(description = "Free-text notes", required = false) String notes,
            @ToolArg(description = "Whether this is a fractional-share transaction", required = false) Boolean isFractional,
            @ToolArg(description = "Multiplier applied for fractional shares", required = false) BigDecimal fractionalMultiplier,
            @ToolArg(description = "Currency the commission was charged in, if different from `currency`", required = false) Currency commissionCurrency,
            @ToolArg(description = "Exchange the instrument trades on, e.g. NASDAQ") @NotBlank @Size(max = 20) String exchange,
            @ToolArg(description = "ISO country code of the exchange, e.g. US") @NotBlank @Size(max = 50) String country,
            @ToolArg(description = "Human-readable company/instrument name", required = false) String companyName) {
        return failureTranslator.sanitize(createTransactionUseCase.execute(mapper.toCreateCommand(
                        identity.requireUserId(), ticker, transactionType, assetType, quantity, price, fees,
                        currency, transactionDate, notes, isFractional, fractionalMultiplier, commissionCurrency,
                        exchange, country, companyName))
                .map(result -> switch (result) {
                    case CreateTransactionUseCase.Result.Success success -> mapper.toDto(success.transaction());
                    case CreateTransactionUseCase.Result.InvalidRequest invalid ->
                            throw new ToolCallException("INVALID_REQUEST: " + invalid.message());
                    case CreateTransactionUseCase.Result.Conflict conflict ->
                            throw new ToolCallException("CONFLICT: " + conflict.message());
                }));
    }

    @Tool(name = "get_transaction", description = "Fetches a single transaction by its id.", structuredContent = true)
    Uni<TransactionDto> getTransaction(@ToolArg(description = "Transaction id (UUID)") @NotNull UUID id) {
        return failureTranslator.sanitize(getTransactionUseCase.execute(
                        new GetTransactionUseCase.Query(identity.requireUserId(), id))
                .map(result -> switch (result) {
                    case GetTransactionUseCase.Result.Success success -> mapper.toDto(success.transaction());
                    case GetTransactionUseCase.Result.NotFound notFound ->
                            throw new ToolCallException("NOT_FOUND: transaction " + notFound.id() + " not found");
                }));
    }

    @Tool(name = "search_transactions", description = "Lists or searches the caller's transactions, optionally "
            + "filtered by ticker, type, and date range. Omit all filters to list every transaction.", structuredContent = true)
    Uni<TransactionListDto> searchTransactions(
            @ToolArg(description = "Filter by ticker symbol", required = false) String ticker,
            @ToolArg(description = "Filter by transaction type: BUY, SELL, DIVIDEND, or SPLIT", required = false) TransactionType type,
            @ToolArg(description = "Only include transactions on or after this date (ISO-8601)", required = false) LocalDate fromDate,
            @ToolArg(description = "Only include transactions on or before this date (ISO-8601)", required = false) LocalDate toDate,
            @ToolArg(description = "Sort order by transaction date: ASC or DESC", required = false, defaultValue = "DESC") TransactionSortOrder sort,
            @ToolArg(description = "Maximum number of results to return", required = false) Integer limit,
            @ToolArg(description = "Number of results to skip, for pagination", required = false) Integer offset) {
        return failureTranslator.sanitize(searchTransactionsUseCase.execute(new SearchTransactionsUseCase.Query(
                        identity.requireUserId(), ticker, type, fromDate, toDate, sort, limit, offset))
                .map(result -> switch (result) {
                    case SearchTransactionsUseCase.Result.Success success ->
                            TransactionListDto.of(success.transactions().stream().map(mapper::toDto).toList());
                    case SearchTransactionsUseCase.Result.InvalidRequest invalid ->
                            throw new ToolCallException("INVALID_REQUEST: " + invalid.message());
                }));
    }

    @Tool(name = "update_transaction", description = "Updates fields of an existing transaction in place. "
            + "Only the id is required; any other field left unset keeps its current value.", structuredContent = true)
    Uni<TransactionDto> updateTransaction(
            @ToolArg(description = "Transaction id (UUID) to update") @NotNull UUID id,
            @ToolArg(description = "Ticker symbol, e.g. AAPL", required = false) @Size(max = 20) String ticker,
            @ToolArg(description = "BUY, SELL, DIVIDEND, or SPLIT", required = false) TransactionType transactionType,
            @ToolArg(description = "Number of shares/units traded; must be at least 0.000001 if present", required = false) @DecimalMin("0.000001") BigDecimal quantity,
            @ToolArg(description = "Price per share/unit; must be zero or positive if present", required = false) @DecimalMin("0.0") BigDecimal price,
            @ToolArg(description = "Commission/fees paid, if any", required = false) BigDecimal fees,
            @ToolArg(description = "ISO currency code of the price, e.g. USD", required = false) Currency currency,
            @ToolArg(description = "Date the transaction occurred (ISO-8601, YYYY-MM-DD)", required = false) LocalDate transactionDate,
            @ToolArg(description = "Free-text notes", required = false) String notes,
            @ToolArg(description = "Whether this is a fractional-share transaction", required = false) Boolean isFractional,
            @ToolArg(description = "Multiplier applied for fractional shares", required = false) BigDecimal fractionalMultiplier,
            @ToolArg(description = "Currency the commission was charged in, if different from `currency`", required = false) Currency commissionCurrency,
            @ToolArg(description = "Exchange the instrument trades on, e.g. NASDAQ", required = false) @Size(max = 20) String exchange,
            @ToolArg(description = "ISO country code of the exchange, e.g. US", required = false) @Size(max = 50) String country,
            @ToolArg(description = "Human-readable company/instrument name", required = false) String companyName) {
        return failureTranslator.sanitize(updateTransactionUseCase.execute(mapper.toUpdateCommand(
                        identity.requireUserId(), id, ticker, transactionType, quantity, price, fees, currency,
                        transactionDate, notes, isFractional, fractionalMultiplier, commissionCurrency, exchange,
                        country, companyName))
                .map(result -> switch (result) {
                    case UpdateTransactionUseCase.Result.Success success -> mapper.toDto(success.transaction());
                    case UpdateTransactionUseCase.Result.NotFound notFound ->
                            throw new ToolCallException("NOT_FOUND: transaction " + notFound.id() + " not found");
                    case UpdateTransactionUseCase.Result.InvalidRequest invalid ->
                            throw new ToolCallException("INVALID_REQUEST: " + invalid.message());
                    case UpdateTransactionUseCase.Result.Conflict conflict ->
                            throw new ToolCallException("CONFLICT: " + conflict.message());
                }));
    }

    @Tool(name = "delete_transaction", description = "Deletes a transaction from the ledger.", structuredContent = true)
    Uni<DeleteConfirmationDto> deleteTransaction(@ToolArg(description = "Transaction id (UUID) to delete") @NotNull UUID id) {
        return failureTranslator.sanitize(deleteTransactionUseCase.execute(
                        new DeleteTransactionUseCase.Command(identity.requireUserId(), id))
                .map(result -> switch (result) {
                    case DeleteTransactionUseCase.Result.Success success ->
                            new DeleteConfirmationDto(true, success.id());
                    case DeleteTransactionUseCase.Result.NotFound notFound ->
                            throw new ToolCallException("NOT_FOUND: transaction " + notFound.id() + " not found");
                    case DeleteTransactionUseCase.Result.Conflict conflict ->
                            throw new ToolCallException("CONFLICT: " + conflict.message());
                }));
    }

    @Tool(name = "count_transactions", description = "Counts the caller's transactions, optionally scoped to one ticker.", structuredContent = true)
    Uni<TransactionCountDto> countTransactions(
            @ToolArg(description = "Count only transactions for this ticker", required = false) String ticker) {
        return failureTranslator.sanitize(countTransactionsUseCase.execute(
                        new CountTransactionsUseCase.Query(identity.requireUserId(), ticker))
                .map(result -> switch (result) {
                    case CountTransactionsUseCase.Result.Success success -> new TransactionCountDto(success.count());
                }));
    }
}

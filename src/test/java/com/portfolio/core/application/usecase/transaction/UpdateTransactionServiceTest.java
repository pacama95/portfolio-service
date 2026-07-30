package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.UpdateTransactionUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.ProviderSymbolMappingPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateTransactionServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private final ProviderSymbolMappingPort providerSymbolMappings =
            Mockito.mock(ProviderSymbolMappingPort.class);
    private UpdateTransactionService service;

    @BeforeEach
    void setUp() {
        when(providerSymbolMappings.invalidate(any())).thenReturn(Uni.createFrom().voidItem());
        service = new UpdateTransactionService(repository, marketDataPort, providerSymbolMappings);
    }

    @Test
    void givenPartialUpdate_whenExecute_thenMergesAndReturnsSuccess() {
        UUID id = UUID.randomUUID();
        Transaction existing = transaction(id, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.of(existing)));
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());

        UpdateTransactionUseCase.Result result = service.execute(new UpdateTransactionUseCase.Command(
                USER, id, null, null, null, new BigDecimal("120"), null, null, null, "updated notes",
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        UpdateTransactionUseCase.Result.Success success =
                assertInstanceOf(UpdateTransactionUseCase.Result.Success.class, result);
        assertEquals("AAPL", success.transaction().ticker());
        assertEquals(0, new BigDecimal("10").compareTo(success.transaction().quantity()));
        assertEquals(0, new BigDecimal("120").compareTo(success.transaction().price()));
        assertEquals("updated notes", success.transaction().notes());
    }

    @Test
    void givenMissingTransaction_whenExecute_thenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.empty()));

        UpdateTransactionUseCase.Result result = service.execute(new UpdateTransactionUseCase.Command(
                USER, id, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(UpdateTransactionUseCase.Result.NotFound.class, result);
    }

    @Test
    void givenNonPositiveQuantity_whenExecute_thenInvalidRequest() {
        UUID id = UUID.randomUUID();

        UpdateTransactionUseCase.Result result = service.execute(new UpdateTransactionUseCase.Command(
                USER, id, null, null, BigDecimal.ZERO, null, null, null, null, null,
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(UpdateTransactionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNegativePrice_whenExecute_thenInvalidRequest() {
        UUID id = UUID.randomUUID();

        UpdateTransactionUseCase.Result result = service.execute(new UpdateTransactionUseCase.Command(
                USER, id, null, null, null, new BigDecimal("-1"), null, null, null, null,
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(UpdateTransactionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenBlankListingMetadata_whenExecute_thenInvalidRequest() {
        UUID id = UUID.randomUUID();

        for (UpdateTransactionUseCase.Command command : List.of(
                update(id, null, null, null, null, null, null, null, null, null, null, null, " ", null, null),
                update(id, null, null, null, null, null, null, null, null, null, null, null, null, " ", null))) {
            UpdateTransactionUseCase.Result result = service.execute(command)
                    .subscribe().withSubscriber(UniAssertSubscriber.create())
                    .awaitItem()
                    .getItem();

            assertInstanceOf(UpdateTransactionUseCase.Result.InvalidRequest.class, result);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void givenSuccessfulUpdate_whenExecute_thenInvalidatesProviderSymbolMapping() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        service.execute(update(id, null, null, null, new BigDecimal("120"), null, null, null, null,
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        verify(providerSymbolMappings).invalidate("AAPL");
    }

    @Test
    void givenRenamedTicker_whenExecute_thenInvalidatesBothOldAndNewSymbol() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        service.execute(update(id, "msft", null, null, null, null, null, null, null,
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        verify(providerSymbolMappings).invalidate("MSFT");
        verify(providerSymbolMappings).invalidate("AAPL");
    }

    @Test
    void givenMissingTransaction_whenExecute_thenLeavesProviderSymbolMappingAlone() {
        UUID id = UUID.randomUUID();
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.empty()));

        service.execute(update(id, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        verify(providerSymbolMappings, never()).invalidate(any());
    }

    @Test
    void givenLowercaseTicker_whenExecute_thenUppercasesTicker() {
        UUID id = UUID.randomUUID();
        Transaction existing = transaction(id, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.of(existing)));
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());

        service.execute(new UpdateTransactionUseCase.Command(
                USER, id, "msft", null, null, null, null, null, null, null,
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository).save(captor.capture());
        assertEquals("MSFT", captor.getValue().ticker());
    }

    @Test
    void givenBlankTicker_whenExecute_thenTrimsAndUppercases() {
        UUID id = UUID.randomUUID();
        Transaction existing = transaction(id, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.of(existing)));
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());

        service.execute(new UpdateTransactionUseCase.Command(
                USER, id, "  msft  ", null, null, null, null, null, null, null,
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository).save(captor.capture());
        assertEquals("MSFT", captor.getValue().ticker());
    }

    @Test
    void givenNullQuantity_whenExecute_thenKeepsExistingQuantity() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, new BigDecimal("120"), null, null, null, null,
                        null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals(0, new BigDecimal("10").compareTo(success.transaction().quantity()));
    }

    @Test
    void givenOnlyTransactionTypeUpdate_whenExecute_thenUpdatesTransactionType() {
        UUID id = UUID.randomUUID();
        Transaction existing = fullTransaction(id);
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.of(existing)));
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        // Turning this BUY into a SELL only makes ledger sense with prior shares to sell.
        Transaction priorBuy = new Transaction(
                UUID.randomUUID(), USER, "AAPL", TransactionType.BUY, AssetType.COMMON_STOCK,
                new BigDecimal("20"), new BigDecimal("90"), BigDecimal.ZERO, Currency.USD,
                LocalDate.of(2023, 12, 1), null, false, BigDecimal.ONE, Currency.USD,
                "NASDAQ", "US", "Apple Inc",
                OffsetDateTime.parse("2023-12-01T00:00:00Z"), OffsetDateTime.parse("2023-12-01T00:00:00Z"));
        stubLock(Map.of("AAPL", List.of(existing, priorBuy)));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, TransactionType.SELL, null, null, null, null, null, null,
                        null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals(TransactionType.SELL, success.transaction().transactionType());
    }

    @Test
    void givenTransactionTypeUpdateWouldOversell_whenExecute_thenConflict() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        UpdateTransactionUseCase.Result result = service.execute(update(
                id, null, TransactionType.SELL, null, null, null, null, null, null,
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(UpdateTransactionUseCase.Result.Conflict.class, result);
    }

    @Test
    void givenTickerChangeWouldOversellOldTicker_whenExecute_thenConflict() {
        UUID buyId = UUID.randomUUID();
        Transaction buy = fullTransaction(buyId);
        Transaction sell = new Transaction(
                UUID.randomUUID(), USER, "AAPL", TransactionType.SELL, AssetType.COMMON_STOCK,
                new BigDecimal("10"), new BigDecimal("110"), BigDecimal.ZERO, Currency.USD,
                LocalDate.of(2024, 2, 1), null, false, BigDecimal.ONE, Currency.USD,
                "NASDAQ", "US", "Apple Inc",
                OffsetDateTime.parse("2024-02-01T00:00:00Z"), OffsetDateTime.parse("2024-02-01T00:00:00Z"));
        when(repository.findById(USER, buyId)).thenReturn(Uni.createFrom().item(Optional.of(buy)));
        stubLock(Map.of("AAPL", List.of(buy, sell)));

        // Moving the BUY away to MSFT would leave the AAPL SELL with nothing to sell.
        UpdateTransactionUseCase.Result result = service.execute(update(
                buyId, "MSFT", null, null, null, null, null, null, null,
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(UpdateTransactionUseCase.Result.Conflict.class, result);
    }

    @Test
    void givenTickerChangeKeepsNewTickerLedgerValid_whenExecute_thenSuccess() {
        UUID id = UUID.randomUUID();
        Transaction existing = fullTransaction(id);
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.of(existing)));
        Transaction msftSell = new Transaction(
                UUID.randomUUID(), USER, "MSFT", TransactionType.SELL, AssetType.COMMON_STOCK,
                new BigDecimal("5"), new BigDecimal("200"), BigDecimal.ZERO, Currency.USD,
                LocalDate.of(2024, 2, 1), null, false, BigDecimal.ONE, Currency.USD,
                "NASDAQ", "US", "Microsoft Inc",
                OffsetDateTime.parse("2024-02-01T00:00:00Z"), OffsetDateTime.parse("2024-02-01T00:00:00Z"));
        stubLock(Map.of("AAPL", List.of(existing), "MSFT", List.of(msftSell)));
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });

        // Moving this BUY to MSFT keeps it BUY-typed via the command below (ticker only changes);
        // the pre-existing MSFT SELL on 2/1 has shares to sell once this BUY (1/1) lands there.
        UpdateTransactionUseCase.Result result = service.execute(update(
                id, "MSFT", null, null, null, null, null, null, null,
                null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(UpdateTransactionUseCase.Result.Success.class, result);
    }

    @Test
    void givenOnlyQuantityUpdate_whenExecute_thenUpdatesQuantity() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, new BigDecimal("7"), null, null, null, null, null,
                        null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals(0, new BigDecimal("7").compareTo(success.transaction().quantity()));
    }

    @Test
    void givenOnlyFeesUpdate_whenExecute_thenUpdatesFees() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, new BigDecimal("3"), null, null, null,
                        null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals(0, new BigDecimal("3").compareTo(success.transaction().fees()));
    }

    @Test
    void givenOnlyCurrencyUpdate_whenExecute_thenUpdatesCurrency() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, null, Currency.EUR, null, null,
                        null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals(Currency.EUR, success.transaction().currency());
    }

    @Test
    void givenOnlyTransactionDateUpdate_whenExecute_thenUpdatesTransactionDate() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));
        LocalDate newDate = LocalDate.of(2024, 6, 1);

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, null, null, newDate, null,
                        null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals(newDate, success.transaction().transactionDate());
    }

    @Test
    void givenOnlyFractionalUpdate_whenExecute_thenUpdatesFractional() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, null, null, null, null,
                        true, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals(true, success.transaction().fractional());
    }

    @Test
    void givenOnlyFractionalMultiplierUpdate_whenExecute_thenUpdatesFractionalMultiplier() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, null, null, null, null,
                        null, new BigDecimal("0.00000002"), null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals(0, new BigDecimal("0.00000002").compareTo(success.transaction().fractionalMultiplier()));
    }

    @Test
    void givenOnlyCommissionCurrencyUpdate_whenExecute_thenUpdatesCommissionCurrency() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, null, null, null, null,
                        null, null, Currency.GBP, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals(Currency.GBP, success.transaction().commissionCurrency());
    }

    @Test
    void givenOnlyExchangeUpdate_whenExecute_thenUpdatesExchange() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, null, null, null, null,
                        null, null, null, "LSE", null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals("LSE", success.transaction().exchange());
    }

    @Test
    void givenOnlyCountryUpdate_whenExecute_thenUpdatesCountry() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, null, null, null, null,
                        null, null, null, null, "GB", null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals("GB", success.transaction().country());
    }

    @Test
    void givenOnlyCompanyNameUpdate_whenExecute_thenUpdatesCompanyName() {
        UUID id = UUID.randomUUID();
        stubExisting(id, fullTransaction(id));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, "Updated Corp"))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals("Updated Corp", success.transaction().companyName());
    }

    @Test
    void givenNewFeesWithForeignCommissionCurrency_whenExecute_thenConvertsFees() {
        UUID id = UUID.randomUUID();
        Transaction existing = transactionWithCommission(id, Currency.USD, Currency.EUR, new BigDecimal("1"));
        stubExisting(id, existing);
        when(marketDataPort.getFxRate(Currency.EUR, Currency.USD))
                .thenReturn(Uni.createFrom().item(new BigDecimal("1.10")));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, new BigDecimal("2"), null, null, null,
                        null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        // 2 EUR * 1.10 EUR/USD = 2.20 USD
        assertEquals(0, new BigDecimal("2.20").compareTo(success.transaction().fees()));
    }

    @Test
    void givenFxFailure_whenExecute_thenFeesFallBackToRateOne() {
        UUID id = UUID.randomUUID();
        Transaction existing = transactionWithCommission(id, Currency.USD, Currency.EUR, new BigDecimal("1"));
        stubExisting(id, existing);
        when(marketDataPort.getFxRate(Currency.EUR, Currency.USD))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("fx down")));

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, new BigDecimal("2"), null, null, null,
                        null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals(0, new BigDecimal("2").compareTo(success.transaction().fees()));
    }

    @Test
    void givenUnrelatedFieldUpdate_whenFeesNotSupplied_thenExistingFeesCarriedOverWithoutReconversion() {
        UUID id = UUID.randomUUID();
        // Fees already converted to USD at some earlier write; commissionCurrency (EUR) still
        // differs from currency (USD) -- must NOT be re-converted just because it's unrelated
        // fields being updated here, or it would be double-converted on every subsequent edit.
        Transaction existing = transactionWithCommission(id, Currency.USD, Currency.EUR, new BigDecimal("2.20"));
        stubExisting(id, existing);

        UpdateTransactionUseCase.Result.Success success = assertInstanceOf(
                UpdateTransactionUseCase.Result.Success.class,
                service.execute(update(id, null, null, null, null, null, null, null, "new notes",
                        null, null, null, null, null, null))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem());

        assertEquals(0, new BigDecimal("2.20").compareTo(success.transaction().fees()));
        verify(marketDataPort, never()).getFxRate(any(), any());
    }

    private static Transaction transactionWithCommission(
            UUID id, Currency currency, Currency commissionCurrency, BigDecimal fees) {
        return new Transaction(
                id,
                USER,
                "AAPL",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("10"),
                new BigDecimal("100"),
                fees,
                currency,
                LocalDate.of(2024, 1, 1),
                "notes",
                false,
                BigDecimal.ONE,
                commissionCurrency,
                "NASDAQ",
                "US",
                "Apple Inc",
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));
    }

    private void stubExisting(UUID id, Transaction existing) {
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.of(existing)));
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());
    }

    @SuppressWarnings("unchecked")
    private void stubLock(Map<String, List<Transaction>> byTicker) {
        when(repository.withTickersLocked(any(), any(), any())).thenAnswer(invocation -> {
            Function<Map<String, List<Transaction>>, Uni<Object>> action = invocation.getArgument(2);
            return action.apply(byTicker);
        });
    }

    private static UpdateTransactionUseCase.Command update(
            UUID id,
            String ticker,
            TransactionType transactionType,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal fees,
            Currency currency,
            LocalDate transactionDate,
            String notes,
            Boolean fractional,
            BigDecimal fractionalMultiplier,
            Currency commissionCurrency,
            String exchange,
            String country,
            String companyName) {
        return new UpdateTransactionUseCase.Command(
                USER, id, ticker, transactionType, quantity, price, fees, currency, transactionDate, notes,
                fractional, fractionalMultiplier, commissionCurrency, exchange, country, companyName);
    }

    private static Transaction fullTransaction(UUID id) {
        return new Transaction(
                id,
                USER,
                "AAPL",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("10"),
                new BigDecimal("100"),
                new BigDecimal("1"),
                Currency.USD,
                LocalDate.of(2024, 1, 1),
                "notes",
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "Apple Inc",
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));
    }

    private static Transaction transaction(UUID id, String ticker, BigDecimal quantity, BigDecimal price) {
        return new Transaction(
                id,
                USER,
                ticker,
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                quantity,
                price,
                BigDecimal.ZERO,
                Currency.USD,
                LocalDate.of(2024, 1, 1),
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                ticker + " Inc",
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-01T00:00:00Z"));
    }
}

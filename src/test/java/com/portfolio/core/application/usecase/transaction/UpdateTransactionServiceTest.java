package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.UpdateTransactionUseCase;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateTransactionServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
    private UpdateTransactionService service;

    @BeforeEach
    void setUp() {
        service = new UpdateTransactionService(repository);
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
    void givenLowercaseTicker_whenExecute_thenUppercasesTicker() {
        UUID id = UUID.randomUUID();
        Transaction existing = transaction(id, "AAPL", new BigDecimal("10"), new BigDecimal("100"));
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.of(existing)));
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });

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
        stubExisting(id, fullTransaction(id));

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

    private void stubExisting(UUID id, Transaction existing) {
        when(repository.findById(USER, id)).thenReturn(Uni.createFrom().item(Optional.of(existing)));
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
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

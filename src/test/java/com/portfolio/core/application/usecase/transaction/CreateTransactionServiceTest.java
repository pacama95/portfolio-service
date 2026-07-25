package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.model.event.TransactionCreatedEvent;
import com.portfolio.core.ports.incoming.CreateTransactionUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.TransactionEventPublisher;
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
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateTransactionServiceTest {

    private final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
    private final TransactionEventPublisher eventPublisher = Mockito.mock(TransactionEventPublisher.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private CreateTransactionService service;

    @BeforeEach
    void setUp() {
        when(eventPublisher.publishTransactionCreated(any())).thenReturn(Uni.createFrom().voidItem());
        service = new CreateTransactionService(repository, eventPublisher, marketDataPort);
    }

    @Test
    void givenValidCommand_whenExecute_thenSavesAndReturnsSuccess() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());

        CreateTransactionUseCase.Command command = new CreateTransactionUseCase.Command(
                UserId.of("user-1"),
                "aapl",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("10"),
                new BigDecimal("100"),
                new BigDecimal("1"),
                Currency.USD,
                LocalDate.of(2024, 1, 1),
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "Apple");

        CreateTransactionUseCase.Result result = service.execute(command)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        CreateTransactionUseCase.Result.Success success =
                assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
        assertEquals("AAPL", success.transaction().ticker());
        assertEquals(UserId.of("user-1"), success.transaction().userId());

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository).save(captor.capture());
        assertEquals("AAPL", captor.getValue().ticker());

        ArgumentCaptor<TransactionCreatedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionCreatedEvent.class);
        verify(eventPublisher).publishTransactionCreated(eventCaptor.capture());
        TransactionCreatedEvent publishedEvent = eventCaptor.getValue();
        assertEquals(success.transaction(), publishedEvent.transaction());
        assertEquals("AAPL", publishedEvent.transaction().ticker());
        assertNotNull(publishedEvent.eventId());
        assertNotNull(publishedEvent.occurredAt());
    }

    @Test
    void givenPublisherFails_whenExecute_thenResultIsStillSuccess() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());
        when(eventPublisher.publishTransactionCreated(any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("redis down")));

        CreateTransactionUseCase.Result result = service.execute(validCommandBuilder().build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
    }

    @Test
    void givenSellExceedingExistingHoldings_whenExecute_thenConflict() {
        UserId userId = UserId.of("user-1");
        Transaction existingBuy = new Transaction(
                UUID.randomUUID(), userId, "AAPL", TransactionType.BUY, AssetType.COMMON_STOCK,
                new BigDecimal("5"), new BigDecimal("100"), BigDecimal.ZERO, Currency.USD,
                LocalDate.of(2024, 1, 1), null, false, BigDecimal.ONE, Currency.USD,
                "NASDAQ", "US", "Apple",
                OffsetDateTime.parse("2024-01-01T00:00:00Z"), OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        stubLock(Map.of("AAPL", List.of(existingBuy)));

        CreateTransactionUseCase.Command command = new CreateTransactionUseCase.Command(
                userId,
                "AAPL",
                TransactionType.SELL,
                AssetType.COMMON_STOCK,
                new BigDecimal("10"),
                new BigDecimal("110"),
                BigDecimal.ZERO,
                Currency.USD,
                LocalDate.of(2024, 1, 2),
                null,
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "Apple");

        CreateTransactionUseCase.Result result = service.execute(command)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(CreateTransactionUseCase.Result.Conflict.class, result);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishTransactionCreated(any());
    }

    @Test
    void givenNullTicker_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result result = service.execute(validCommandBuilder().ticker(null).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, result);
        verify(eventPublisher, never()).publishTransactionCreated(any());
    }

    @Test
    void givenBlankTicker_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result result = service.execute(validCommandBuilder().ticker("  ").build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNonPositiveQuantity_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result zeroQty = service.execute(
                validCommandBuilder().quantity(BigDecimal.ZERO).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, zeroQty);

        CreateTransactionUseCase.Result negativeQty = service.execute(
                validCommandBuilder().quantity(new BigDecimal("-1")).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, negativeQty);
    }

    @Test
    void givenNegativePrice_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().price(new BigDecimal("-0.01")).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenMissingRequiredFields_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result nullType = service.execute(
                validCommandBuilder().transactionType(null).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, nullType);

        CreateTransactionUseCase.Result nullAsset = service.execute(
                validCommandBuilder().assetType(null).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, nullAsset);

        CreateTransactionUseCase.Result nullCurrency = service.execute(
                validCommandBuilder().currency(null).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, nullCurrency);

        CreateTransactionUseCase.Result nullDate = service.execute(
                validCommandBuilder().transactionDate(null).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, nullDate);
    }

    @Test
    void givenZeroPrice_whenExecute_thenSavesSuccessfully() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());

        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().price(BigDecimal.ZERO).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        CreateTransactionUseCase.Result.Success success =
                assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
        assertEquals(0, BigDecimal.ZERO.compareTo(success.transaction().price()));
    }

    @Test
    void givenNullQuantity_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().quantity(null).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNullPrice_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().price(null).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNullFees_whenExecute_thenDefaultsToZero() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());

        CreateTransactionUseCase.Result result = service.execute(validCommandBuilder().fees(null).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        CreateTransactionUseCase.Result.Success success =
                assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
        assertEquals(0, BigDecimal.ZERO.compareTo(success.transaction().fees()));
    }

    @Test
    void givenNullFractional_whenExecute_thenDefaultsToFalse() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());

        CreateTransactionUseCase.Result result = service.execute(validCommandBuilder().fractional(null).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        CreateTransactionUseCase.Result.Success success =
                assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
        assertFalse(success.transaction().fractional());
    }

    @Test
    void givenNullFractionalMultiplier_whenExecute_thenDefaultsToOne() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());

        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().fractionalMultiplier(null).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        CreateTransactionUseCase.Result.Success success =
                assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
        assertEquals(0, BigDecimal.ONE.compareTo(success.transaction().fractionalMultiplier()));
    }

    @Test
    void givenForeignCommissionCurrency_whenExecute_thenFeesConvertedToTransactionCurrency() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());
        when(marketDataPort.getFxRate(Currency.EUR, Currency.USD))
                .thenReturn(Uni.createFrom().item(new BigDecimal("1.10")));

        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().fees(new BigDecimal("2")).commissionCurrency(Currency.EUR).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        CreateTransactionUseCase.Result.Success success =
                assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
        // 2 EUR * 1.10 EUR/USD = 2.20 USD; commissionCurrency preserved as originally billed.
        assertEquals(0, new BigDecimal("2.20").compareTo(success.transaction().fees()));
        assertEquals(Currency.EUR, success.transaction().commissionCurrency());
    }

    @Test
    void givenForeignCommissionCurrencyAndFxFailure_whenExecute_thenFallsBackToRateOne() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());
        when(marketDataPort.getFxRate(Currency.EUR, Currency.USD))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("fx down")));

        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().fees(new BigDecimal("2")).commissionCurrency(Currency.EUR).build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        CreateTransactionUseCase.Result.Success success =
                assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
        assertEquals(0, new BigDecimal("2").compareTo(success.transaction().fees()));
    }

    @Test
    void givenSameCommissionCurrency_whenExecute_thenNeverCallsFxRate() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });
        stubLock(Map.of());

        service.execute(validCommandBuilder().build())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem();

        verify(marketDataPort, never()).getFxRate(any(), any());
    }

    private void stubLock(Map<String, List<Transaction>> byTicker) {
        when(repository.withTickersLocked(any(), any(), any())).thenAnswer(invocation -> {
            Function<Map<String, List<Transaction>>, Uni<Object>> action = invocation.getArgument(2);
            return action.apply(byTicker);
        });
    }

    private static CommandBuilder validCommandBuilder() {
        return new CommandBuilder();
    }

    private static final class CommandBuilder {
        private final UserId userId = UserId.of("user-1");
        private String ticker = "aapl";
        private TransactionType transactionType = TransactionType.BUY;
        private AssetType assetType = AssetType.COMMON_STOCK;
        private BigDecimal quantity = new BigDecimal("10");
        private BigDecimal price = new BigDecimal("100");
        private BigDecimal fees = new BigDecimal("1");
        private Currency currency = Currency.USD;
        private LocalDate transactionDate = LocalDate.of(2024, 1, 1);
        private String notes = null;
        private Boolean fractional = false;
        private BigDecimal fractionalMultiplier = BigDecimal.ONE;
        private Currency commissionCurrency = Currency.USD;
        private String exchange = "NASDAQ";
        private String country = "US";
        private String companyName = "Apple";

        CommandBuilder ticker(String ticker) { this.ticker = ticker; return this; }
        CommandBuilder transactionType(TransactionType transactionType) { this.transactionType = transactionType; return this; }
        CommandBuilder assetType(AssetType assetType) { this.assetType = assetType; return this; }
        CommandBuilder quantity(BigDecimal quantity) { this.quantity = quantity; return this; }
        CommandBuilder price(BigDecimal price) { this.price = price; return this; }
        CommandBuilder fees(BigDecimal fees) { this.fees = fees; return this; }
        CommandBuilder currency(Currency currency) { this.currency = currency; return this; }
        CommandBuilder transactionDate(LocalDate transactionDate) { this.transactionDate = transactionDate; return this; }
        CommandBuilder fractional(Boolean fractional) { this.fractional = fractional; return this; }
        CommandBuilder fractionalMultiplier(BigDecimal fractionalMultiplier) { this.fractionalMultiplier = fractionalMultiplier; return this; }
        CommandBuilder commissionCurrency(Currency commissionCurrency) { this.commissionCurrency = commissionCurrency; return this; }

        CreateTransactionUseCase.Command build() {
            return new CreateTransactionUseCase.Command(
                    userId, ticker, transactionType, assetType, quantity, price, fees, currency,
                    transactionDate, notes, fractional, fractionalMultiplier,
                    commissionCurrency, exchange, country, companyName);
        }
    }
}

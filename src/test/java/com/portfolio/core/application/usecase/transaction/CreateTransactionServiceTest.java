package com.portfolio.core.application.usecase.transaction;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.CreateTransactionUseCase;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateTransactionServiceTest {

    private final TransactionRepository repository = Mockito.mock(TransactionRepository.class);
    private CreateTransactionService service;

    @BeforeEach
    void setUp() {
        service = new CreateTransactionService(repository);
    }

    @Test
    void givenValidCommand_whenExecute_thenSavesAndReturnsSuccess() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });

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

        CreateTransactionUseCase.Result result = service.execute(command).await().indefinitely();

        CreateTransactionUseCase.Result.Success success =
                assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
        assertEquals("AAPL", success.transaction().ticker());
        assertEquals(UserId.of("user-1"), success.transaction().userId());

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(repository).save(captor.capture());
        assertEquals("AAPL", captor.getValue().ticker());
    }

    @Test
    void givenNullTicker_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result result = service.execute(validCommandBuilder().ticker(null).build())
                .await().indefinitely();

        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenBlankTicker_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result result = service.execute(validCommandBuilder().ticker("  ").build())
                .await().indefinitely();

        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNonPositiveQuantity_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result zeroQty = service.execute(
                validCommandBuilder().quantity(BigDecimal.ZERO).build()).await().indefinitely();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, zeroQty);

        CreateTransactionUseCase.Result negativeQty = service.execute(
                validCommandBuilder().quantity(new BigDecimal("-1")).build()).await().indefinitely();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, negativeQty);
    }

    @Test
    void givenNegativePrice_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().price(new BigDecimal("-0.01")).build()).await().indefinitely();

        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenMissingRequiredFields_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result nullType = service.execute(
                validCommandBuilder().transactionType(null).build()).await().indefinitely();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, nullType);

        CreateTransactionUseCase.Result nullAsset = service.execute(
                validCommandBuilder().assetType(null).build()).await().indefinitely();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, nullAsset);

        CreateTransactionUseCase.Result nullCurrency = service.execute(
                validCommandBuilder().currency(null).build()).await().indefinitely();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, nullCurrency);

        CreateTransactionUseCase.Result nullDate = service.execute(
                validCommandBuilder().transactionDate(null).build()).await().indefinitely();
        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, nullDate);
    }

    @Test
    void givenZeroPrice_whenExecute_thenSavesSuccessfully() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });

        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().price(BigDecimal.ZERO).build()).await().indefinitely();

        CreateTransactionUseCase.Result.Success success =
                assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
        assertEquals(0, BigDecimal.ZERO.compareTo(success.transaction().price()));
    }

    @Test
    void givenNullQuantity_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().quantity(null).build()).await().indefinitely();

        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNullPrice_whenExecute_thenInvalidRequest() {
        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().price(null).build()).await().indefinitely();

        assertInstanceOf(CreateTransactionUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNullFees_whenExecute_thenDefaultsToZero() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });

        CreateTransactionUseCase.Result result = service.execute(validCommandBuilder().fees(null).build())
                .await().indefinitely();

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

        CreateTransactionUseCase.Result result = service.execute(validCommandBuilder().fractional(null).build())
                .await().indefinitely();

        CreateTransactionUseCase.Result.Success success =
                assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
        assertEquals(false, success.transaction().fractional());
    }

    @Test
    void givenNullFractionalMultiplier_whenExecute_thenDefaultsToOne() {
        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0, Transaction.class);
            return Uni.createFrom().item(saved);
        });

        CreateTransactionUseCase.Result result = service.execute(
                validCommandBuilder().fractionalMultiplier(null).build()).await().indefinitely();

        CreateTransactionUseCase.Result.Success success =
                assertInstanceOf(CreateTransactionUseCase.Result.Success.class, result);
        assertEquals(0, BigDecimal.ONE.compareTo(success.transaction().fractionalMultiplier()));
    }

    private static CommandBuilder validCommandBuilder() {
        return new CommandBuilder();
    }

    private static final class CommandBuilder {
        private UserId userId = UserId.of("user-1");
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

        CreateTransactionUseCase.Command build() {
            return new CreateTransactionUseCase.Command(
                    userId, ticker, transactionType, assetType, quantity, price, fees, currency,
                    transactionDate, notes, fractional, fractionalMultiplier,
                    commissionCurrency, exchange, country, companyName);
        }
    }
}

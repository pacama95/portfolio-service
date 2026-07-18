package com.portfolio.adapters.incoming.rest.mapper;

import com.portfolio.adapters.incoming.rest.dto.CreateTransactionRequest;
import com.portfolio.adapters.incoming.rest.dto.TransactionResponse;
import com.portfolio.adapters.incoming.rest.dto.UpdateTransactionRequest;
import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.CreateTransactionUseCase;
import com.portfolio.core.ports.incoming.UpdateTransactionUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionRestMapperTest {

    private TransactionRestMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TransactionRestMapperImpl();
    }

    @Test
    void givenNullTransaction_whenToResponse_thenReturnsNull() {
        assertNull(mapper.toResponse(null));
    }

    @Test
    void givenFullTransaction_whenToResponse_thenMapsAllFieldsIncludingComputedValues() {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2024-01-15T10:00:00Z");
        OffsetDateTime updatedAt = OffsetDateTime.parse("2024-01-16T11:30:00Z");
        Transaction transaction = new Transaction(
                id,
                UserId.of("user-123"),
                "AAPL",
                TransactionType.BUY,
                AssetType.COMMON_STOCK,
                new BigDecimal("10.5"),
                new BigDecimal("150.25"),
                new BigDecimal("9.99"),
                Currency.USD,
                LocalDate.of(2024, 1, 10),
                "test notes",
                true,
                new BigDecimal("0.00000001"),
                Currency.EUR,
                "NASDAQ",
                "US",
                "Apple Inc.",
                createdAt,
                updatedAt);

        TransactionResponse response = mapper.toResponse(transaction);

        assertEquals(id, response.id());
        assertEquals("AAPL", response.ticker());
        assertEquals(TransactionType.BUY, response.transactionType());
        assertEquals(AssetType.COMMON_STOCK, response.assetType());
        assertEquals(new BigDecimal("10.5"), response.quantity());
        assertEquals(new BigDecimal("150.25"), response.price());
        assertEquals(new BigDecimal("9.99"), response.fees());
        assertEquals(Currency.USD, response.currency());
        assertEquals(LocalDate.of(2024, 1, 10), response.transactionDate());
        assertEquals("test notes", response.notes());
        assertTrue(response.isFractional());
        assertEquals(new BigDecimal("0.00000001"), response.fractionalMultiplier());
        assertEquals(Currency.EUR, response.commissionCurrency());
        assertEquals("NASDAQ", response.exchange());
        assertEquals("US", response.country());
        assertEquals("Apple Inc.", response.companyName());
        assertEquals(new BigDecimal("1577.625"), response.totalValue());
        assertEquals(new BigDecimal("1587.615"), response.totalCost());
        assertEquals(createdAt, response.createdAt());
        assertEquals(updatedAt, response.updatedAt());
    }

    @Test
    void givenCreateRequest_whenToCreateCommand_thenWiresAllFields() {
        UserId userId = UserId.of("user-456");
        CreateTransactionRequest request = new CreateTransactionRequest(
                "msft",
                TransactionType.SELL,
                AssetType.ETF,
                new BigDecimal("5"),
                new BigDecimal("300"),
                new BigDecimal("2.50"),
                Currency.GBP,
                LocalDate.of(2024, 3, 20),
                "sell notes",
                true,
                new BigDecimal("0.00000002"),
                Currency.CAD,
                "NYSE",
                "CA",
                "Microsoft Corp.");

        CreateTransactionUseCase.Command command = mapper.toCreateCommand(userId, request);

        assertEquals(userId, command.userId());
        assertEquals("msft", command.ticker());
        assertEquals(TransactionType.SELL, command.transactionType());
        assertEquals(AssetType.ETF, command.assetType());
        assertEquals(new BigDecimal("5"), command.quantity());
        assertEquals(new BigDecimal("300"), command.price());
        assertEquals(new BigDecimal("2.50"), command.fees());
        assertEquals(Currency.GBP, command.currency());
        assertEquals(LocalDate.of(2024, 3, 20), command.transactionDate());
        assertEquals("sell notes", command.notes());
        assertEquals(true, command.fractional());
        assertEquals(new BigDecimal("0.00000002"), command.fractionalMultiplier());
        assertEquals(Currency.CAD, command.commissionCurrency());
        assertEquals("NYSE", command.exchange());
        assertEquals("CA", command.country());
        assertEquals("Microsoft Corp.", command.companyName());
    }

    @Test
    void givenUpdateRequest_whenToUpdateCommand_thenWiresAllFields() {
        UserId userId = UserId.of("user-789");
        UUID transactionId = UUID.randomUUID();
        UpdateTransactionRequest request = new UpdateTransactionRequest(
                "goog",
                TransactionType.DIVIDEND,
                new BigDecimal("1.25"),
                new BigDecimal("140.00"),
                new BigDecimal("0.75"),
                Currency.JPY,
                LocalDate.of(2024, 6, 1),
                "update notes",
                false,
                BigDecimal.ONE,
                Currency.USD,
                "NASDAQ",
                "US",
                "Alphabet Inc.");

        UpdateTransactionUseCase.Command command = mapper.toUpdateCommand(userId, transactionId, request);

        assertEquals(userId, command.userId());
        assertEquals(transactionId, command.id());
        assertEquals("goog", command.ticker());
        assertEquals(TransactionType.DIVIDEND, command.transactionType());
        assertEquals(new BigDecimal("1.25"), command.quantity());
        assertEquals(new BigDecimal("140.00"), command.price());
        assertEquals(new BigDecimal("0.75"), command.fees());
        assertEquals(Currency.JPY, command.currency());
        assertEquals(LocalDate.of(2024, 6, 1), command.transactionDate());
        assertEquals("update notes", command.notes());
        assertEquals(false, command.fractional());
        assertEquals(BigDecimal.ONE, command.fractionalMultiplier());
        assertEquals(Currency.USD, command.commissionCurrency());
        assertEquals("NASDAQ", command.exchange());
        assertEquals("US", command.country());
        assertEquals("Alphabet Inc.", command.companyName());
    }
}

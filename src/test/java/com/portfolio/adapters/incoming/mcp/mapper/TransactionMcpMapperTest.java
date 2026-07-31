package com.portfolio.adapters.incoming.mcp.mapper;

import com.portfolio.adapters.incoming.mcp.dto.TransactionDto;
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

class TransactionMcpMapperTest {

    private TransactionMcpMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TransactionMcpMapperImpl();
    }

    @Test
    void givenNullTransaction_whenToDto_thenReturnsNull() {
        assertNull(mapper.toDto(null));
    }

    @Test
    void givenFullTransaction_whenToDto_thenMapsAllFieldsIncludingComputedValues() {
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

        TransactionDto dto = mapper.toDto(transaction);

        assertEquals(id, dto.id());
        assertEquals("AAPL", dto.ticker());
        assertEquals(TransactionType.BUY, dto.transactionType());
        assertEquals(AssetType.COMMON_STOCK, dto.assetType());
        assertEquals(new BigDecimal("10.5"), dto.quantity());
        assertEquals(new BigDecimal("150.25"), dto.price());
        assertEquals(new BigDecimal("9.99"), dto.fees());
        assertEquals(Currency.USD, dto.currency());
        assertEquals(LocalDate.of(2024, 1, 10), dto.transactionDate());
        assertEquals("test notes", dto.notes());
        assertTrue(dto.isFractional());
        assertEquals(new BigDecimal("0.00000001"), dto.fractionalMultiplier());
        assertEquals(Currency.EUR, dto.commissionCurrency());
        assertEquals("NASDAQ", dto.exchange());
        assertEquals("US", dto.country());
        assertEquals("Apple Inc.", dto.companyName());
        assertEquals(new BigDecimal("1577.625"), dto.totalValue());
        assertEquals(new BigDecimal("1587.615"), dto.totalCost());
        assertEquals(createdAt, dto.createdAt());
        assertEquals(updatedAt, dto.updatedAt());
    }

    @Test
    void givenArgs_whenToCreateCommand_thenWiresAllFieldsAndFractionalRename() {
        UserId userId = UserId.of("user-456");

        CreateTransactionUseCase.Command command = mapper.toCreateCommand(
                userId,
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
    void givenArgs_whenToUpdateCommand_thenWiresAllFieldsAndFractionalRename() {
        UserId userId = UserId.of("user-789");
        UUID transactionId = UUID.randomUUID();

        UpdateTransactionUseCase.Command command = mapper.toUpdateCommand(
                userId,
                transactionId,
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

    @Test
    void givenNullOptionalFieldsOnUpdate_whenToUpdateCommand_thenIdAndUserIdStillWired() {
        UserId userId = UserId.of("user-999");
        UUID transactionId = UUID.randomUUID();

        UpdateTransactionUseCase.Command command = mapper.toUpdateCommand(
                userId, transactionId, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(userId, command.userId());
        assertEquals(transactionId, command.id());
        assertNull(command.ticker());
        assertNull(command.fractional());
    }
}

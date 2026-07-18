package com.portfolio.core.application.usecase.position;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Position;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPositionsUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class GetPositionsServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private GetPositionsService service;

    @BeforeEach
    void setUp() {
        service = new GetPositionsService(transactionRepository, marketDataPort);
    }

    @Test
    void givenNoTransactions_whenExecute_thenEmptyList() {
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(List.of()));

        GetPositionsUseCase.Result result = service.execute(GetPositionsUseCase.Query.all(USER))
                .await().indefinitely();

        GetPositionsUseCase.Result.Success success =
                assertInstanceOf(GetPositionsUseCase.Result.Success.class, result);
        assertTrue(success.positions().isEmpty());
    }

    @Test
    void givenActiveOnly_whenExecute_thenFiltersInactivePositions() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "10", "110", LocalDate.of(2024, 1, 2)),
                tx("MSFT", TransactionType.BUY, "5", "200", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.getSpotPrice("MSFT")).thenReturn(Uni.createFrom().item(new BigDecimal("210")));

        GetPositionsUseCase.Result result = service.execute(GetPositionsUseCase.Query.active(USER))
                .await().indefinitely();

        GetPositionsUseCase.Result.Success success =
                assertInstanceOf(GetPositionsUseCase.Result.Success.class, result);
        assertEquals(1, success.positions().size());
        assertEquals("MSFT", success.positions().getFirst().ticker());
        assertTrue(success.positions().getFirst().active());
    }

    @Test
    void givenSpotPriceFailure_whenExecute_thenNullLatestMarketPrice() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.getSpotPrice("AAPL"))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("market data down")));

        GetPositionsUseCase.Result result = service.execute(GetPositionsUseCase.Query.all(USER))
                .await().indefinitely();

        GetPositionsUseCase.Result.Success success =
                assertInstanceOf(GetPositionsUseCase.Result.Success.class, result);
        Position position = success.positions().getFirst();
        assertEquals("AAPL", position.ticker());
        assertNull(position.latestMarketPrice());
    }

    @Test
    void givenSpotPriceAvailable_whenExecute_thenEnrichesPositions() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findAll(USER)).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.getSpotPrice("AAPL")).thenReturn(Uni.createFrom().item(new BigDecimal("150")));

        GetPositionsUseCase.Result result = service.execute(GetPositionsUseCase.Query.all(USER))
                .await().indefinitely();

        GetPositionsUseCase.Result.Success success =
                assertInstanceOf(GetPositionsUseCase.Result.Success.class, result);
        Position position = success.positions().getFirst();
        assertEquals(0, new BigDecimal("150").compareTo(position.latestMarketPrice()));
        assertEquals(0, new BigDecimal("1500.000000").compareTo(position.marketValue()));
    }

    private static Transaction tx(
            String ticker, TransactionType type, String qty, String price, LocalDate date) {
        return new Transaction(
                UUID.randomUUID(),
                USER,
                ticker,
                type,
                AssetType.COMMON_STOCK,
                new BigDecimal(qty),
                new BigDecimal(price),
                BigDecimal.ZERO,
                Currency.USD,
                date,
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

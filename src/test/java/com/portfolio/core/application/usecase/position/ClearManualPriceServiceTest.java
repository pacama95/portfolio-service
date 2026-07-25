package com.portfolio.core.application.usecase.position;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.ClearManualPriceUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClearManualPriceServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private ClearManualPriceService service;

    @BeforeEach
    void setUp() {
        service = new ClearManualPriceService(transactionRepository, marketDataPort);
    }

    @Test
    void givenValidPosition_whenExecute_thenClearsAndReturnsSuccess() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.clearManualPrice("AAPL")).thenReturn(Uni.createFrom().voidItem());

        ClearManualPriceUseCase.Result result = service.execute(
                new ClearManualPriceUseCase.Command(USER, "aapl"))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        ClearManualPriceUseCase.Result.Success success =
                assertInstanceOf(ClearManualPriceUseCase.Result.Success.class, result);
        assertEquals("AAPL", success.position().ticker());
        verify(marketDataPort).clearManualPrice("AAPL");
    }

    @Test
    void givenNoTransactions_whenExecute_thenNotFound() {
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(List.of()));

        ClearManualPriceUseCase.Result result = service.execute(
                new ClearManualPriceUseCase.Command(USER, "AAPL"))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        ClearManualPriceUseCase.Result.NotFound notFound =
                assertInstanceOf(ClearManualPriceUseCase.Result.NotFound.class, result);
        assertEquals("AAPL", notFound.ticker());
    }

    @Test
    void givenInvalidLedger_whenExecute_thenNotFound() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "5", "100", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "10", "110", LocalDate.of(2024, 1, 2)));
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(txs));

        ClearManualPriceUseCase.Result result = service.execute(
                new ClearManualPriceUseCase.Command(USER, "AAPL"))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        assertInstanceOf(ClearManualPriceUseCase.Result.NotFound.class, result);
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

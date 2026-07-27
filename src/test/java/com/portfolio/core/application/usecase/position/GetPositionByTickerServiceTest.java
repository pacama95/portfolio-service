package com.portfolio.core.application.usecase.position;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.Position;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.GetPositionByTickerUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.MarketDataProviderError;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetPositionByTickerServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private GetPositionByTickerService service;

    @BeforeEach
    void setUp() {
        service = new GetPositionByTickerService(transactionRepository, marketDataPort);
    }

    @Test
    void givenValidTransactions_whenExecute_thenSuccess() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.getSpotPrice("AAPL")).thenReturn(Uni.createFrom().item(new BigDecimal("150")));

        GetPositionByTickerUseCase.Result result = service.execute(
                new GetPositionByTickerUseCase.Query(USER, "aapl"))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPositionByTickerUseCase.Result.Success success =
                assertInstanceOf(GetPositionByTickerUseCase.Result.Success.class, result);
        Position position = success.position();
        assertEquals("AAPL", position.ticker());
        assertEquals(0, new BigDecimal("10").compareTo(position.sharesOwned()));
        assertEquals(0, new BigDecimal("150").compareTo(position.latestMarketPrice()));
    }

    @Test
    void givenNoTransactions_whenExecute_thenNotFound() {
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(List.of()));

        GetPositionByTickerUseCase.Result result = service.execute(
                new GetPositionByTickerUseCase.Query(USER, "AAPL"))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPositionByTickerUseCase.Result.NotFound notFound =
                assertInstanceOf(GetPositionByTickerUseCase.Result.NotFound.class, result);
        assertEquals("AAPL", notFound.ticker());
    }

    @Test
    void givenOversellReplay_whenExecute_thenNotFound() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "5", "100", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "10", "100", LocalDate.of(2024, 1, 2)));
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(txs));

        GetPositionByTickerUseCase.Result result = service.execute(
                new GetPositionByTickerUseCase.Query(USER, "AAPL"))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPositionByTickerUseCase.Result.NotFound notFound =
                assertInstanceOf(GetPositionByTickerUseCase.Result.NotFound.class, result);
        assertEquals("AAPL", notFound.ticker());
    }

    @Test
    void givenLowercaseTicker_whenExecute_thenQueriesUppercasedTicker() {
        List<Transaction> txs = List.of(
                tx("MSFT", TransactionType.BUY, "2", "200", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findByTicker(USER, "MSFT")).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.getSpotPrice("MSFT")).thenReturn(Uni.createFrom().item(new BigDecimal("210")));

        service.execute(new GetPositionByTickerUseCase.Query(USER, " msft "))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        Mockito.verify(transactionRepository).findByTicker(USER, "MSFT");
    }

    @Test
    void givenSpotPriceFailure_whenExecute_thenPropagatesTypedFailure() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(txs));
        when(marketDataPort.getSpotPrice("AAPL"))
                .thenReturn(Uni.createFrom().failure(
                        new MarketDataProviderError.SymbolResolution("AAPL", "no exact result")));

        service.execute(
                new GetPositionByTickerUseCase.Query(USER, "AAPL"))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure()
                .assertFailedWith(MarketDataProviderError.SymbolResolution.class);
    }

    @Test
    void givenPriceNotRequested_whenExecute_thenReturnsPositionWithoutCallingMarketData() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(txs));

        GetPositionByTickerUseCase.Result result = service.execute(
                GetPositionByTickerUseCase.Query.withoutMarketPrice(USER, "AAPL"))
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .getItem();

        GetPositionByTickerUseCase.Result.Success success =
                assertInstanceOf(GetPositionByTickerUseCase.Result.Success.class, result);
        assertNull(success.position().latestMarketPrice());
        verify(marketDataPort, never()).getSpotPrice("AAPL");
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

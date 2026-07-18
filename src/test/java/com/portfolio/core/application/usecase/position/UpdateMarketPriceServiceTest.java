package com.portfolio.core.application.usecase.position;

import com.portfolio.core.model.AssetType;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.QuoteSource;
import com.portfolio.core.model.SpotQuote;
import com.portfolio.core.model.SpotQuoteKind;
import com.portfolio.core.model.Transaction;
import com.portfolio.core.model.TransactionType;
import com.portfolio.core.model.UserId;
import com.portfolio.core.ports.incoming.UpdateMarketPriceUseCase;
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
import static org.mockito.Mockito.when;

class UpdateMarketPriceServiceTest {

    private static final UserId USER = UserId.of("user-1");

    private final TransactionRepository transactionRepository = Mockito.mock(TransactionRepository.class);
    private final MarketDataPort marketDataPort = Mockito.mock(MarketDataPort.class);
    private UpdateMarketPriceService service;

    @BeforeEach
    void setUp() {
        service = new UpdateMarketPriceService(transactionRepository, marketDataPort);
    }

    @Test
    void givenValidPosition_whenExecute_thenSuccess() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "10", "100", LocalDate.of(2024, 1, 1)));
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(txs));
        SpotQuote quote = new SpotQuote(
                SpotQuoteKind.PRICE, "AAPL", new BigDecimal("175"), Currency.USD,
                null, null, OffsetDateTime.parse("2024-06-01T12:00:00Z"), QuoteSource.MANUAL);
        when(marketDataPort.setManualPrice("AAPL", new BigDecimal("175"), Currency.USD))
                .thenReturn(Uni.createFrom().item(quote));

        UpdateMarketPriceUseCase.Result result = service.execute(
                new UpdateMarketPriceUseCase.Command(USER, "aapl", new BigDecimal("175"), Currency.USD))
                .await().indefinitely();

        UpdateMarketPriceUseCase.Result.Success success =
                assertInstanceOf(UpdateMarketPriceUseCase.Result.Success.class, result);
        assertEquals("AAPL", success.position().ticker());
        assertEquals(0, new BigDecimal("175").compareTo(success.position().latestMarketPrice()));
    }

    @Test
    void givenNegativePrice_whenExecute_thenInvalidRequest() {
        UpdateMarketPriceUseCase.Result result = service.execute(
                new UpdateMarketPriceUseCase.Command(USER, "AAPL", new BigDecimal("-1"), Currency.USD))
                .await().indefinitely();

        assertInstanceOf(UpdateMarketPriceUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenNullPrice_whenExecute_thenInvalidRequest() {
        UpdateMarketPriceUseCase.Result result = service.execute(
                new UpdateMarketPriceUseCase.Command(USER, "AAPL", null, Currency.USD))
                .await().indefinitely();

        assertInstanceOf(UpdateMarketPriceUseCase.Result.InvalidRequest.class, result);
    }

    @Test
    void givenInvalidLedger_whenExecute_thenNotFound() {
        List<Transaction> txs = List.of(
                tx("AAPL", TransactionType.BUY, "5", "100", LocalDate.of(2024, 1, 1)),
                tx("AAPL", TransactionType.SELL, "10", "110", LocalDate.of(2024, 1, 2)));
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(txs));

        UpdateMarketPriceUseCase.Result result = service.execute(
                new UpdateMarketPriceUseCase.Command(USER, "AAPL", new BigDecimal("100"), Currency.USD))
                .await().indefinitely();

        assertInstanceOf(UpdateMarketPriceUseCase.Result.NotFound.class, result);
    }

    @Test
    void givenNoTransactions_whenExecute_thenNotFound() {
        when(transactionRepository.findByTicker(USER, "AAPL")).thenReturn(Uni.createFrom().item(List.of()));

        UpdateMarketPriceUseCase.Result result = service.execute(
                new UpdateMarketPriceUseCase.Command(USER, "AAPL", new BigDecimal("100"), Currency.USD))
                .await().indefinitely();

        UpdateMarketPriceUseCase.Result.NotFound notFound =
                assertInstanceOf(UpdateMarketPriceUseCase.Result.NotFound.class, result);
        assertEquals("AAPL", notFound.ticker());
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

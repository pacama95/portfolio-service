package com.portfolio.core.application.usecase.portfolio;

import com.portfolio.core.domain.LedgerReplay;
import com.portfolio.core.model.Currency;
import com.portfolio.core.model.PortfolioSummary;
import com.portfolio.core.model.Position;
import com.portfolio.core.ports.incoming.GetPortfolioSummaryUseCase;
import com.portfolio.core.ports.outgoing.MarketDataPort;
import com.portfolio.core.ports.outgoing.TransactionRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class GetPortfolioSummaryService implements GetPortfolioSummaryUseCase {

    private static final Logger LOG = Logger.getLogger(GetPortfolioSummaryService.class);

    private final TransactionRepository transactionRepository;
    private final MarketDataPort marketDataPort;
    private final Currency baseCurrency;

    public GetPortfolioSummaryService(
            TransactionRepository transactionRepository,
            MarketDataPort marketDataPort,
            @ConfigProperty(name = "application.portfolio.base-currency", defaultValue = "USD")
            String baseCurrency) {
        this.transactionRepository = transactionRepository;
        this.marketDataPort = marketDataPort;
        this.baseCurrency = Currency.valueOf(baseCurrency);
    }

    @Override
    public Uni<Result> execute(Query query) {
        LOG.infof("Getting portfolio summary activeOnly=%s baseCurrency=%s", query.activeOnly(), baseCurrency);
        return transactionRepository.findAll(query.userId())
                .map(LedgerReplay::replayAll)
                .map(positions -> query.activeOnly()
                        ? positions.stream().filter(Position::active).toList()
                        : positions)
                .flatMap(this::enrichAndSummarize)
                .invoke(summary -> LOG.infof(
                        "Portfolio summary positions=%d active=%d marketValue=%s",
                        summary.totalPositions(), summary.activePositions(), summary.totalMarketValue()))
                .map(Result.Success::new);
    }

    private Uni<PortfolioSummary> enrichAndSummarize(List<Position> positions) {
        if (positions.isEmpty()) {
            return Uni.createFrom().item(PortfolioSummary.of(
                    baseCurrency, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0));
        }
        Uni<List<Position>> enriched = Uni.createFrom().item(new ArrayList<Position>());
        for (Position position : positions) {
            enriched = enriched.flatMap(list -> marketDataPort.getSpotPrice(position.ticker())
                    .onFailure().recoverWithItem(failure -> {
                        LOG.warnf(failure, "Spot price unavailable ticker=%s; continuing without price",
                                position.ticker());
                        return null;
                    })
                    .flatMap(price -> convertToBase(position.withLatestMarketPrice(price))
                            .map(converted -> {
                                list.add(converted);
                                return list;
                            })));
        }
        return enriched.map(list -> {
            BigDecimal marketValue = list.stream()
                    .map(Position::marketValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cost = list.stream()
                    .map(p -> p.totalInvestedAmount() != null ? p.totalInvestedAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int active = (int) list.stream().filter(Position::active).count();
            return PortfolioSummary.of(baseCurrency, marketValue, cost, list.size(), active);
        });
    }

    private Uni<Position> convertToBase(Position position) {
        if (position.currency() == null || position.currency() == baseCurrency) {
            return Uni.createFrom().item(position);
        }
        return marketDataPort.getFxRate(position.currency(), baseCurrency)
                .onFailure().recoverWithItem(failure -> {
                    LOG.warnf(failure,
                            "FX rate unavailable from=%s to=%s; falling back to rate=1",
                            position.currency(), baseCurrency);
                    return BigDecimal.ONE;
                })
                .map(rate -> {
                    BigDecimal invested = scale(position.totalInvestedAmount(), rate);
                    BigDecimal fees = scale(position.totalTransactionFees(), rate);
                    BigDecimal price = scale(position.latestMarketPrice(), rate);
                    BigDecimal avg = scale(position.averageCostPerShare(), rate);
                    return new Position(
                            position.ticker(),
                            position.companyName(),
                            position.sharesOwned(),
                            avg,
                            invested,
                            fees,
                            baseCurrency,
                            price,
                            position.firstPurchaseDate(),
                            position.active(),
                            position.exchange(),
                            position.country());
                });
    }

    private static BigDecimal scale(BigDecimal value, BigDecimal rate) {
        if (value == null) {
            return null;
        }
        return value.multiply(rate).setScale(6, RoundingMode.HALF_UP);
    }
}

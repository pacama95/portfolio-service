package com.portfolio.core.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@RegisterForReflection
public record Position(
        String ticker,
        String companyName,
        BigDecimal sharesOwned,
        BigDecimal averageCostPerShare,
        BigDecimal totalInvestedAmount,
        BigDecimal totalTransactionFees,
        Currency currency,
        BigDecimal latestMarketPrice,
        LocalDate firstPurchaseDate,
        boolean active,
        String exchange,
        String country
) {

    public BigDecimal marketValue() {
        if (latestMarketPrice == null || sharesOwned == null) {
            return BigDecimal.ZERO;
        }
        return sharesOwned.multiply(latestMarketPrice).setScale(6, RoundingMode.HALF_UP);
    }

    public BigDecimal unrealizedGainLoss() {
        return marketValue().subtract(totalInvestedAmount != null ? totalInvestedAmount : BigDecimal.ZERO);
    }

    public BigDecimal unrealizedGainLossPercentage() {
        if (totalInvestedAmount == null || totalInvestedAmount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return unrealizedGainLoss()
                .multiply(BigDecimal.valueOf(100))
                .divide(totalInvestedAmount, 6, RoundingMode.HALF_UP);
    }

    public Position withLatestMarketPrice(BigDecimal price) {
        return new Position(
                ticker, companyName, sharesOwned, averageCostPerShare, totalInvestedAmount,
                totalTransactionFees, currency, price, firstPurchaseDate, active, exchange, country);
    }
}

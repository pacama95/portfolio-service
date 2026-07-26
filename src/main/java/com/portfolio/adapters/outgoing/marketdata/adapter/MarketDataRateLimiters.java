package com.portfolio.adapters.outgoing.marketdata.adapter;

import com.portfolio.adapters.common.ReactiveRateLimiter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * One limiter instance per provider so each provider's budget is configured independently.
 * Each provider has its own producer and configuration keys.
 */
@ApplicationScoped
public class MarketDataRateLimiters {

    public static final String TWELVE_DATA = "twelvedata-rate-limiter";
    public static final String EODHD = "eodhd-rate-limiter";

    @Produces
    @Singleton
    @Named(TWELVE_DATA)
    ReactiveRateLimiter twelveDataRateLimiter(
            @ConfigProperty(name = "application.market-data.twelve-data.rate-limit.credits-per-minute",
                    defaultValue = "8")
            int creditsPerMinute,
            @ConfigProperty(name = "application.market-data.twelve-data.rate-limit.max-wait",
                    defaultValue = "PT2M")
            Duration maxWait) {
        return new ReactiveRateLimiter("twelvedata", creditsPerMinute, maxWait);
    }

    @Produces
    @Singleton
    @Named(EODHD)
    ReactiveRateLimiter eodhdRateLimiter(
            @ConfigProperty(name = "application.market-data.eodhd.rate-limit.requests-per-minute",
                    defaultValue = "1000")
            int requestsPerMinute,
            @ConfigProperty(name = "application.market-data.eodhd.rate-limit.max-wait",
                    defaultValue = "PT2M")
            Duration maxWait) {
        return new ReactiveRateLimiter("eodhd", requestsPerMinute, maxWait);
    }
}

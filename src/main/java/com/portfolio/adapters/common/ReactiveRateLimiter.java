package com.portfolio.adapters.common;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Non-blocking min-spacing rate limiter: consecutive {@link #acquire()} completions are spaced
 * at least {@code 60s / creditsPerMinute} apart, which keeps any rolling minute at or under the
 * configured budget. Callers whose projected wait exceeds {@code maxWait} fail fast with
 * {@link RateLimitExceededException} instead of queueing unboundedly; a rejection never
 * consumes a slot. Plain class (no CDI annotations) so each provider can get its own
 * independently configured instance via a producer.
 */
public class ReactiveRateLimiter {

    private static final Logger LOG = Logger.getLogger(ReactiveRateLimiter.class);

    private final String name;
    private final long spacingNanos;
    private final long maxWaitNanos;

    private long nextSlotNanos = Long.MIN_VALUE;

    public ReactiveRateLimiter(String name, int creditsPerMinute, Duration maxWait) {
        if (creditsPerMinute < 1) {
            throw new IllegalArgumentException("creditsPerMinute must be >= 1, got " + creditsPerMinute);
        }
        this.name = name;
        this.spacingNanos = Duration.ofMinutes(1).toNanos() / creditsPerMinute;
        this.maxWaitNanos = maxWait.toNanos();
    }

    /**
     * Reserves the next available slot and completes once it is due. Fails with
     * {@link RateLimitExceededException} (without reserving) when the queue is already
     * {@code maxWait} deep.
     */
    public Uni<Void> acquire() {
        long waitNanos = reserveSlot();
        if (waitNanos < 0) {
            Duration retryAfter = Duration.ofNanos(-waitNanos);
            LOG.warnf("Rate limiter '%s' queue full; rejecting caller retryAfter=%ss",
                    name, retryAfter.toSeconds());
            return Uni.createFrom().failure(new RateLimitExceededException(name, retryAfter));
        }
        if (waitNanos == 0) {
            return Uni.createFrom().voidItem();
        }
        LOG.infof("Rate limiter '%s' delaying caller by %dms", name, waitNanos / 1_000_000);
        return Uni.createFrom().voidItem().onItem().delayIt().by(Duration.ofNanos(waitNanos));
    }

    /** Returns nanos to wait for the reserved slot, or the negated projected wait if rejected. */
    private synchronized long reserveSlot() {
        long now = System.nanoTime();
        long slot = Math.max(nextSlotNanos, now);
        long waitNanos = slot - now;
        if (waitNanos > maxWaitNanos) {
            return -waitNanos;
        }
        nextSlotNanos = slot + spacingNanos;
        return waitNanos;
    }

    public static class RateLimitExceededException extends RuntimeException {
        private final Duration retryAfter;

        public RateLimitExceededException(String limiterName, Duration retryAfter) {
            super("Rate limiter '" + limiterName + "' saturated; retry after "
                    + retryAfter.toSeconds() + "s");
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }
}

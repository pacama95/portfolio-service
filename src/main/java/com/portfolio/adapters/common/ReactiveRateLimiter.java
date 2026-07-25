package com.portfolio.adapters.common;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Arrays;

/**
 * Non-blocking rolling-window rate limiter: at most {@code creditsPerMinute} {@link #acquire()}
 * completions fall in any 60s window. Within that budget callers are not spaced out at all, so an
 * idle limiter lets a full burst through immediately. Only once the window is spent does the next caller wait, and just
 * until the oldest grant ages out. Callers whose projected wait exceeds {@code maxWait} fail fast
 * with {@link RateLimitExceededException} instead of queueing unboundedly; a rejection never
 * consumes a slot. Plain class (no CDI annotations) so each provider can get its own
 * independently configured instance via a producer.
 */
public class ReactiveRateLimiter {

    private static final Logger LOG = Logger.getLogger(ReactiveRateLimiter.class);

    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

    private final String name;
    private final long maxWaitNanos;

    /** Ring of the last {@code creditsPerMinute} granted slot times, oldest at {@link #cursor}. */
    private final long[] grants;
    private int cursor = 0;

    public ReactiveRateLimiter(String name, int creditsPerMinute, Duration maxWait) {
        if (creditsPerMinute < 1) {
            throw new IllegalArgumentException("creditsPerMinute must be >= 1, got " + creditsPerMinute);
        }
        this.name = name;
        this.maxWaitNanos = maxWait.toNanos();
        this.grants = new long[creditsPerMinute];
        // Seed a fully expired window so the first burst is unthrottled.
        Arrays.fill(this.grants, System.nanoTime() - WINDOW_NANOS);
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
        // The oldest of the last `creditsPerMinute` grants frees its credit one window after it fired.
        long slot = Math.max(grants[cursor] + WINDOW_NANOS, now);
        long waitNanos = slot - now;
        if (waitNanos > maxWaitNanos) {
            return -waitNanos;
        }
        grants[cursor] = slot;
        cursor = (cursor + 1) % grants.length;
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

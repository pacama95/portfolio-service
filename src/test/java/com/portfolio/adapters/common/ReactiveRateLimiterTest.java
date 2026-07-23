package com.portfolio.adapters.common;

import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveRateLimiterTest {

    @Test
    void givenIdleLimiter_whenAcquire_thenCompletesImmediately() {
        ReactiveRateLimiter limiter = new ReactiveRateLimiter("test", 8, Duration.ofMinutes(2));

        limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(1));
    }

    @Test
    void givenConsumedSlot_whenAcquireAgain_thenCompletesAfterSpacing() {
        // 600 credits/min => 100ms spacing
        ReactiveRateLimiter limiter = new ReactiveRateLimiter("test", 600, Duration.ofMinutes(2));

        limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(1));

        long start = System.nanoTime();
        limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis >= 80, "second acquire should wait ~100ms, waited " + elapsedMillis + "ms");
    }

    @Test
    void givenQueueBeyondMaxWait_whenAcquire_thenFailsFastWithRetryAfter() {
        // 1 credit/min, zero max-wait: the second caller projects a 60s wait and is rejected.
        ReactiveRateLimiter limiter = new ReactiveRateLimiter("test", 1, Duration.ZERO);

        limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(1));

        Throwable failure = limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure(Duration.ofSeconds(1))
                .getFailure();

        ReactiveRateLimiter.RateLimitExceededException rejected =
                assertInstanceOf(ReactiveRateLimiter.RateLimitExceededException.class, failure);
        assertTrue(rejected.retryAfter().compareTo(Duration.ofSeconds(50)) >= 0,
                "retryAfter should be ~60s, was " + rejected.retryAfter());
    }

    @Test
    void givenRejection_whenLimiterFreesUp_thenSlotWasNotConsumed() {
        // maxWait covers one queued slot (spacing 100ms) but not two.
        ReactiveRateLimiter limiter = new ReactiveRateLimiter("test", 600, Duration.ofMillis(150));

        limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(1));
        UniAssertSubscriber<Void> queued = limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        // Queue is now ~200ms deep; a third caller is rejected...
        limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure(Duration.ofSeconds(1))
                .assertFailedWith(ReactiveRateLimiter.RateLimitExceededException.class);

        queued.awaitItem(Duration.ofSeconds(5));

        // ...and because the rejection consumed nothing, the next caller queues normally.
        limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(5));
    }

    @Test
    void givenConcurrentAcquires_thenAllCompleteSerializedBySpacing() {
        // 3 acquires at 1200 credits/min (50ms spacing) => last completes >= ~100ms in.
        ReactiveRateLimiter limiter = new ReactiveRateLimiter("test", 1200, Duration.ofMinutes(2));

        long start = System.nanoTime();
        UniAssertSubscriber<Void> first = limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        UniAssertSubscriber<Void> second = limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        UniAssertSubscriber<Void> third = limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        first.awaitItem(Duration.ofSeconds(5));
        second.awaitItem(Duration.ofSeconds(5));
        third.awaitItem(Duration.ofSeconds(5));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis >= 80, "third acquire should wait ~100ms, waited " + elapsedMillis + "ms");
    }

    @Test
    void givenNonPositiveCreditsPerMinute_whenConstructing_thenRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ReactiveRateLimiter("test", 0, Duration.ofMinutes(2)));
        assertEquals("creditsPerMinute must be >= 1, got 0", failure.getMessage());
    }
}

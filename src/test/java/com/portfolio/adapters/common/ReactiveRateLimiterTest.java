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
    void givenUnspentWindow_whenAcquiringWholeBudget_thenAllCompleteWithoutDelay() {
        ReactiveRateLimiter limiter = new ReactiveRateLimiter("test", 8, Duration.ofMinutes(2));

        long start = System.nanoTime();
        for (int i = 0; i < 8; i++) {
            limiter.acquire()
                    .subscribe().withSubscriber(UniAssertSubscriber.create())
                    .awaitItem(Duration.ofSeconds(1));
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 500,
                "a full minute's budget should burst through, took " + elapsedMillis + "ms");
    }

    @Test
    void givenSpentWindow_whenAcquireAgain_thenWaitsForOldestCreditToAgeOut() {
        // Budget of 2/min: the third caller waits until the first grant leaves the 60s window.
        ReactiveRateLimiter limiter = new ReactiveRateLimiter("test", 2, Duration.ofMinutes(2));

        limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(1));
        limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(1));

        UniAssertSubscriber<Void> third = limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        third.assertNotTerminated();
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
        // Budget of 1/min with a maxWait that no post-budget wait can satisfy.
        ReactiveRateLimiter limiter = new ReactiveRateLimiter("test", 1, Duration.ofMillis(150));

        limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem(Duration.ofSeconds(1));
        limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure(Duration.ofSeconds(1))
                .assertFailedWith(ReactiveRateLimiter.RateLimitExceededException.class);

        // The rejection consumed nothing, so the retry-after still reflects only the first grant.
        Throwable failure = limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitFailure(Duration.ofSeconds(1))
                .getFailure();

        ReactiveRateLimiter.RateLimitExceededException rejected =
                assertInstanceOf(ReactiveRateLimiter.RateLimitExceededException.class, failure);
        assertTrue(rejected.retryAfter().compareTo(Duration.ofSeconds(60)) <= 0,
                "retryAfter should not have grown past one window, was " + rejected.retryAfter());
    }

    @Test
    void givenConcurrentAcquiresBeyondBudget_thenOnlyTheOverflowIsDelayed() {
        ReactiveRateLimiter limiter = new ReactiveRateLimiter("test", 2, Duration.ofMinutes(2));

        UniAssertSubscriber<Void> first = limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        UniAssertSubscriber<Void> second = limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create());
        UniAssertSubscriber<Void> third = limiter.acquire()
                .subscribe().withSubscriber(UniAssertSubscriber.create());

        first.awaitItem(Duration.ofSeconds(1));
        second.awaitItem(Duration.ofSeconds(1));
        third.assertNotTerminated();
    }

    @Test
    void givenNonPositiveCreditsPerMinute_whenConstructing_thenRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ReactiveRateLimiter("test", 0, Duration.ofMinutes(2)));
        assertEquals("creditsPerMinute must be >= 1, got 0", failure.getMessage());
    }
}

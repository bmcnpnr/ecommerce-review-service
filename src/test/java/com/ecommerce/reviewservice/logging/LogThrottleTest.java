package com.ecommerce.reviewservice.logging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LogThrottleTest {

    private final AtomicLong nanos = new AtomicLong();
    private final LogThrottle throttle = new LogThrottle(10, TimeUnit.SECONDS, nanos::get);

    private void advanceSeconds(long seconds) {
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    @Test
    void firstOccurrenceLogs_withNothingSuppressed() {
        assertThat(throttle.acquire("db-down")).isZero();
    }

    @Test
    void repeatsInsideTheWindowAreSuppressed_andCounted() {
        assertThat(throttle.acquire("db-down")).isZero();

        for (int i = 0; i < 5; i++) {
            advanceSeconds(1);
            assertThat(throttle.acquire("db-down")).isEqualTo(-1);
        }

        advanceSeconds(10);
        assertThat(throttle.acquire("db-down")).as("next window reports the five suppressed lines").isEqualTo(5);
        assertThat(throttle.acquire("db-down")).as("and starts counting again").isEqualTo(-1);
        advanceSeconds(10);
        assertThat(throttle.acquire("db-down")).isEqualTo(1);
    }

    @Test
    void theWindowOpensAtTheLoggedOccurrence_notAtTheLastSuppressedOne() {
        throttle.acquire("k");
        advanceSeconds(9);
        assertThat(throttle.acquire("k")).isEqualTo(-1);
        advanceSeconds(1); // 10 s after the logged line, only 1 s after the last suppressed one
        assertThat(throttle.acquire("k")).isEqualTo(1);
    }

    @Test
    void keysAreIndependent() {
        assertThat(throttle.acquire("a")).isZero();
        assertThat(throttle.acquire("b")).isZero();
        assertThat(throttle.acquire("a")).isEqualTo(-1);
        assertThat(throttle.acquire("b")).isEqualTo(-1);
    }

    @Test
    void trackedKeysAreBoundedAtMaxKeys_theRestShareOneBucket() {
        for (int i = 0; i < LogThrottle.MAX_KEYS; i++) {
            assertThat(throttle.acquire("key-" + i)).as("key %d is tracked on its own", i).isZero();
        }

        // Past the limit every new key lands in the overflow bucket: one line per window for all of them.
        assertThat(throttle.acquire("attacker-1")).isZero();
        assertThat(throttle.acquire("attacker-2")).isEqualTo(-1);
        assertThat(throttle.acquire("attacker-3")).isEqualTo(-1);
        assertThat(throttle.acquire(LogThrottle.OVERFLOW_KEY)).isEqualTo(-1);
        // keys that were tracked before the limit keep their own window
        assertThat(throttle.acquire("key-0")).isEqualTo(-1);

        advanceSeconds(10);
        assertThat(throttle.acquire("attacker-4")).isEqualTo(3);
    }

    @Test
    void concurrentCallers_inOneWindow_logExactlyOnce() throws Exception {
        int threads = 8;
        int perThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> logged = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            logged.add(pool.submit(() -> {
                start.await();
                int allowed = 0;
                for (int i = 0; i < perThread; i++) {
                    if (throttle.acquire("hot") >= 0) {
                        allowed++;
                    }
                }
                return allowed;
            }));
        }
        start.countDown();
        int total = 0;
        for (Future<Integer> f : logged) {
            total += f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(total).isEqualTo(1);
        advanceSeconds(10);
        assertThat(throttle.acquire("hot")).isEqualTo(threads * perThread - 1);
    }

    @Test
    void suppressedSuffix_isEmptyUnlessSomethingWasSuppressed() {
        assertThat(LogThrottle.suppressedSuffix(0)).isEmpty();
        assertThat(LogThrottle.suppressedSuffix(-1)).isEmpty();
        assertThat(LogThrottle.suppressedSuffix(7)).isEqualTo(" (7 similar suppressed)");
    }

    @Test
    void keyOf_picksTheFirstFrameInOurOwnCode() {
        Throwable t = new IllegalStateException("boom");
        t.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.springframework.dao.Foo", "bar", "Foo.java", 10),
                new StackTraceElement("com.ecommerce.reviewservice.service.ReviewService", "createReview", "ReviewService.java", 42),
                new StackTraceElement("com.ecommerce.product.controller.ProductController", "stock", "ProductController.java", 7)
        });

        assertThat(LogThrottle.keyOf(t))
                .isEqualTo("java.lang.IllegalStateException@com.ecommerce.reviewservice.service.ReviewService:42");
    }

    @Test
    void keyOf_fallsBackToTheTopFrame_orJustTheClass() {
        Throwable foreignOnly = new IllegalStateException("boom");
        foreignOnly.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.postgresql.Driver", "connect", "Driver.java", 99)
        });
        Throwable noFrames = new IllegalStateException("boom");
        noFrames.setStackTrace(new StackTraceElement[0]);

        assertThat(LogThrottle.keyOf(foreignOnly)).isEqualTo("java.lang.IllegalStateException@org.postgresql.Driver:99");
        assertThat(LogThrottle.keyOf(noFrames)).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void sameFailureSiteGivesTheSameKey_differentSitesDoNot() {
        assertThat(keyAt(10)).isEqualTo(keyAt(10)).isNotEqualTo(keyAt(11));
    }

    private static String keyAt(int line) {
        Throwable t = new RuntimeException();
        t.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.ecommerce.reviewservice.service.ReviewService", "m", "ReviewService.java", line)
        });
        return LogThrottle.keyOf(t);
    }
}

package com.gamagerecruiters.marketplace.modules.auth.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RateLimitServiceTest {

    private TestClock testClock;
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        testClock = new TestClock(Instant.parse("2026-01-01T12:00:00Z"));
        service = RateLimitService.createForTest(true, 100, testClock);
    }

    @Test
    @DisplayName("Allows requests up to the configured limit")
    void allowsRequestsUpToLimit() {
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> service.check("test-bucket", "ip-1", 5, Duration.ofMinutes(1)));
        }
    }

    @Test
    @DisplayName("The next request after reaching limit throws RateLimitExceededException with correct retryAfterSeconds")
    void nextRequestThrowsWithCorrectRetryAfterSeconds() {
        for (int i = 0; i < 5; i++) {
            service.check("test-bucket", "ip-1", 5, Duration.ofMinutes(1));
        }

        // Advance 20 seconds into the 60-second window (40 seconds remaining)
        testClock.advance(Duration.ofSeconds(20));

        RateLimitExceededException ex = assertThrows(
                RateLimitExceededException.class,
                () -> service.check("test-bucket", "ip-1", 5, Duration.ofMinutes(1))
        );

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertEquals("RATE_LIMITED", ex.getErrorCode());
        assertEquals("Too many requests. Please try again later.", ex.getMessage());
        assertEquals(40, ex.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("A new window resets the limit and allows new requests")
    void newWindowResetsLimit() {
        for (int i = 0; i < 5; i++) {
            service.check("test-bucket", "ip-1", 5, Duration.ofMinutes(1));
        }

        // Next request in same window throws
        assertThrows(RateLimitExceededException.class,
                () -> service.check("test-bucket", "ip-1", 5, Duration.ofMinutes(1)));

        // Advance past the 60-second window
        testClock.advance(Duration.ofSeconds(61));

        // New window allows requests again
        assertDoesNotThrow(() -> service.check("test-bucket", "ip-1", 5, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("Different keys and different buckets are independent")
    void differentKeysAndBucketsAreIndependent() {
        // Exhaust key-1 in bucket-1
        for (int i = 0; i < 2; i++) {
            service.check("bucket-1", "key-1", 2, Duration.ofMinutes(1));
        }
        assertThrows(RateLimitExceededException.class,
                () -> service.check("bucket-1", "key-1", 2, Duration.ofMinutes(1)));

        // key-2 in bucket-1 is independent
        assertDoesNotThrow(() -> service.check("bucket-1", "key-2", 2, Duration.ofMinutes(1)));

        // key-1 in bucket-2 is independent
        assertDoesNotThrow(() -> service.check("bucket-2", "key-1", 2, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("Saturation does not evict active counters: 4th key fails closed with 429, blocked keys stay blocked")
    void saturationDoesNotEvictActiveCounters() {
        RateLimitService cappedService = RateLimitService.createForTest(true, 3, testClock);

        // Fill with 3 keys in "login-ip". Limit = 2, window = 1 minute (60s).
        // Key 1: drive to limit (2 allowed, 3rd blocked)
        cappedService.check("login-ip", "ip-1", 2, Duration.ofMinutes(1));
        cappedService.check("login-ip", "ip-1", 2, Duration.ofMinutes(1));
        assertThrows(RateLimitExceededException.class,
                () -> cappedService.check("login-ip", "ip-1", 2, Duration.ofMinutes(1)));

        // Key 2: drive to limit (2 allowed, 3rd blocked)
        cappedService.check("login-ip", "ip-2", 2, Duration.ofMinutes(1));
        cappedService.check("login-ip", "ip-2", 2, Duration.ofMinutes(1));
        assertThrows(RateLimitExceededException.class,
                () -> cappedService.check("login-ip", "ip-2", 2, Duration.ofMinutes(1)));

        // Key 3: 1 request used (active, not blocked yet)
        cappedService.check("login-ip", "ip-3", 2, Duration.ofMinutes(1));

        assertEquals(3, cappedService.getTrackedKeyCount("login-ip"));

        // Advance 10 seconds into window (50 seconds remaining until windowEnd at T+60s)
        testClock.advance(Duration.ofSeconds(10));

        // Attempt a 4th NEW key -> must fail closed with 429 RATE_LIMITED and retryAfterSeconds ~ 50s
        RateLimitExceededException satEx = assertThrows(
                RateLimitExceededException.class,
                () -> cappedService.check("login-ip", "ip-4", 2, Duration.ofMinutes(1))
        );
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, satEx.getStatus());
        assertEquals("RATE_LIMITED", satEx.getErrorCode());
        assertEquals(50, satEx.getRetryAfterSeconds());

        // CRITICAL CHECK: Blocked keys ip-1 and ip-2 must STILL be blocked (reproduction bypass prevented!)
        assertThrows(RateLimitExceededException.class,
                () -> cappedService.check("login-ip", "ip-1", 2, Duration.ofMinutes(1)));
        assertThrows(RateLimitExceededException.class,
                () -> cappedService.check("login-ip", "ip-2", 2, Duration.ofMinutes(1)));

        // The third existing key can still make its second request normally
        assertDoesNotThrow(() -> cappedService.check("login-ip", "ip-3", 2, Duration.ofMinutes(1)));
        // And now ip-3 is also at its limit, so 3rd request is blocked
        assertThrows(RateLimitExceededException.class,
                () -> cappedService.check("login-ip", "ip-3", 2, Duration.ofMinutes(1)));

        // Total tracked keys still capped at 3
        assertEquals(3, cappedService.getTrackedKeyCount("login-ip"));
    }

    @Test
    @DisplayName("Expired entries are purged first: after windows expire, new keys are accepted and count <= capacity")
    void expiredEntriesPurgedFirstAndNewKeysAccepted() {
        RateLimitService cappedService = RateLimitService.createForTest(true, 3, testClock);

        // Fill capacity 3
        cappedService.check("bucket", "k1", 10, Duration.ofMinutes(1));
        cappedService.check("bucket", "k2", 10, Duration.ofMinutes(1));
        cappedService.check("bucket", "k3", 10, Duration.ofMinutes(1));
        assertEquals(3, cappedService.getTrackedKeyCount("bucket"));

        // Attempting k4 while saturated throws 429
        assertThrows(RateLimitExceededException.class,
                () -> cappedService.check("bucket", "k4", 10, Duration.ofMinutes(1)));

        // Advance past window (65 seconds)
        testClock.advance(Duration.ofSeconds(65));

        // Now new keys should be accepted because expired entries are purged
        assertDoesNotThrow(() -> cappedService.check("bucket", "k4", 10, Duration.ofMinutes(1)));
        assertTrue(cappedService.getTrackedKeyCount("bucket") <= 3);

        assertDoesNotThrow(() -> cappedService.check("bucket", "k5", 10, Duration.ofMinutes(1)));
        assertDoesNotThrow(() -> cappedService.check("bucket", "k6", 10, Duration.ofMinutes(1)));

        // Now capacity 3 is saturated again with active keys k4, k5, k6
        assertThrows(RateLimitExceededException.class,
                () -> cappedService.check("bucket", "k7", 10, Duration.ofMinutes(1)));
        assertEquals(3, cappedService.getTrackedKeyCount("bucket"));
    }

    @Test
    @DisplayName("Distinct-key concurrency: capacity C with many threads results in exactly C successes and <= C tracked keys")
    void distinctKeyConcurrencyNeverExceedsCapacity() throws Exception {
        int capacity = 20;
        int totalThreads = 200;
        RateLimitService concurrentService = RateLimitService.createForTest(true, capacity, testClock);

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rateLimitedCount = new AtomicInteger();

        for (int i = 0; i < totalThreads; i++) {
            final String key = "key-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    concurrentService.check("distinct-bucket", key, 10, Duration.ofMinutes(1));
                    successCount.incrementAndGet();
                } catch (RateLimitExceededException ex) {
                    rateLimitedCount.incrementAndGet();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent test must complete in time");
        executor.shutdown();

        assertEquals(capacity, successCount.get(), "Exactly capacity C requests must succeed");
        assertEquals(totalThreads - capacity, rateLimitedCount.get(), "All excess distinct keys must receive RateLimitExceededException");
        assertTrue(concurrentService.getTrackedKeyCount("distinct-bucket") <= capacity, "Tracked keys must never exceed capacity");
    }

    @Test
    @DisplayName("Concurrent calls from many threads never allow more than the limit for the same key")
    void concurrentCallsNeverAllowMoreThanLimit() throws Exception {
        int limit = 10;
        int totalThreads = 50;

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rateLimitedCount = new AtomicInteger();

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    service.check("concurrent-bucket", "shared-key", limit, Duration.ofMinutes(1));
                    successCount.incrementAndGet();
                } catch (RateLimitExceededException ex) {
                    rateLimitedCount.incrementAndGet();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Concurrent test must complete in time");
        executor.shutdown();

        assertEquals(limit, successCount.get(), "Exactly the limit count must succeed");
        assertEquals(totalThreads - limit, rateLimitedCount.get(), "All excess requests must be rate limited");
    }

    @Test
    @DisplayName("Buckets are isolated: saturating one bucket does not reject new keys in another bucket")
    void bucketsAreIsolated() {
        RateLimitService multiBucketService = RateLimitService.createForTest(true, 2, testClock);

        // Saturate bucket-A with 2 keys
        multiBucketService.check("bucket-A", "user-1", 10, Duration.ofMinutes(1));
        multiBucketService.check("bucket-A", "user-2", 10, Duration.ofMinutes(1));

        // 3rd key in bucket-A fails closed with 429
        assertThrows(RateLimitExceededException.class,
                () -> multiBucketService.check("bucket-A", "user-3", 10, Duration.ofMinutes(1)));

        // bucket-B is unaffected and accepts new keys up to its own capacity
        assertDoesNotThrow(() -> multiBucketService.check("bucket-B", "user-1", 10, Duration.ofMinutes(1)));
        assertDoesNotThrow(() -> multiBucketService.check("bucket-B", "user-2", 10, Duration.ofMinutes(1)));

        // 3rd key in bucket-B is rejected
        assertThrows(RateLimitExceededException.class,
                () -> multiBucketService.check("bucket-B", "user-3", 10, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("If RATE_LIMIT_ENABLED=false the service must not throw")
    void ifDisabledDoesNotThrow() {
        RateLimitService disabledService = RateLimitService.createForTest(false, 100, testClock);

        for (int i = 0; i < 50; i++) {
            assertDoesNotThrow(() -> disabledService.check("any-bucket", "any-key", 2, Duration.ofMinutes(1)));
        }
        assertEquals(0, disabledService.getTrackedKeyCount());
    }

    @Test
    @DisplayName("Convenience methods apply configured bucket limits")
    void convenienceMethodsApplyConfiguredDefaults() {
        // sensitive limit is 5
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> service.checkSensitive("user-10"));
        }
        RateLimitExceededException ex = assertThrows(
                RateLimitExceededException.class,
                () -> service.checkSensitive("user-10")
        );
        assertEquals("RATE_LIMITED", ex.getErrorCode());
    }

    static class TestClock extends Clock {
        private Instant instant;
        private final ZoneId zone = ZoneOffset.UTC;

        TestClock(Instant initialInstant) {
            this.instant = initialInstant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

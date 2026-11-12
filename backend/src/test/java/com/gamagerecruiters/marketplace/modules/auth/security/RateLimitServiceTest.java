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
    @DisplayName("Expired entries are purged and key cap holds")
    void expiredEntriesArePurgedAndKeyCapHolds() {
        RateLimitService cappedService = RateLimitService.createForTest(true, 5, testClock);

        // Fill up to cap with 5 keys
        for (int i = 0; i < 5; i++) {
            cappedService.check("bucket", "key-" + i, 10, Duration.ofMinutes(1));
        }
        assertEquals(5, cappedService.getTrackedKeyCount());

        // Advance clock past the window
        testClock.advance(Duration.ofSeconds(65));

        // Adding a new key should trigger purging of expired keys
        cappedService.check("bucket", "new-key", 10, Duration.ofMinutes(1));
        assertTrue(cappedService.getTrackedKeyCount() <= 5, "Tracked key count must stay <= maxTrackedKeys");

        // Fill non-expired entries up to cap and beyond
        for (int i = 0; i < 10; i++) {
            cappedService.check("bucket", "active-key-" + i, 10, Duration.ofMinutes(1));
        }
        assertTrue(cappedService.getTrackedKeyCount() <= 5, "Tracked key count must never exceed maxTrackedKeys");
    }

    @Test
    @DisplayName("Concurrent calls from many threads never allow more than the limit")
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

package com.gamagerecruiters.marketplace.modules.auth.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * In-memory fixed-window rate limiter with per-bucket bounded memory and atomic capacity enforcement.
 *
 * <p>Capacity ({@code maxTrackedKeys}) is enforced <b>per bucket</b>. When a bucket reaches capacity:
 * <ul>
 *   <li>Expired entries (where {@code now >= windowEnd}) are purged first.</li>
 *   <li>Active counters are <b>never</b> evicted, ensuring blocked callers cannot bypass limits by inserting new keys.</li>
 *   <li>If no room remains after purging, the limiter <b>fails closed</b> for new keys, throwing
 *       {@link RateLimitExceededException} (HTTP 429 RATE_LIMITED) with {@code retryAfterSeconds} set to
 *       the time until the earliest active window in that bucket expires.</li>
 *   <li>Keys already tracked continue to be evaluated and rate-limited normally during saturation.</li>
 * </ul>
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    public static final String BUCKET_LOGIN_IP = "login-ip";
    public static final String BUCKET_LOGIN_ACCOUNT = "login-account";
    public static final String BUCKET_REGISTER = "register";
    public static final String BUCKET_SENSITIVE = "sensitive";

    private final boolean enabled;
    private final int maxTrackedKeys;
    private final int loginIpMaxRequests;
    private final int loginIpWindowMinutes;
    private final int loginAccountMaxRequests;
    private final int loginAccountWindowMinutes;
    private final int registerMaxRequests;
    private final int registerWindowMinutes;
    private final int sensitiveMaxRequests;
    private final int sensitiveWindowMinutes;
    private final Clock clock;

    private final ConcurrentMap<String, WindowCounter> storage = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> bucketKeyCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> bucketLastWarnTimes = new ConcurrentHashMap<>();

    public RateLimitService(
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.max-tracked-keys:10000}") int maxTrackedKeys,
            @Value("${app.rate-limit.login-ip.max-requests:30}") int loginIpMaxRequests,
            @Value("${app.rate-limit.login-ip.window-minutes:15}") int loginIpWindowMinutes,
            @Value("${app.rate-limit.login-account.max-requests:10}") int loginAccountMaxRequests,
            @Value("${app.rate-limit.login-account.window-minutes:15}") int loginAccountWindowMinutes,
            @Value("${app.rate-limit.register.max-requests:10}") int registerMaxRequests,
            @Value("${app.rate-limit.register.window-minutes:60}") int registerWindowMinutes,
            @Value("${app.rate-limit.sensitive.max-requests:5}") int sensitiveMaxRequests,
            @Value("${app.rate-limit.sensitive.window-minutes:15}") int sensitiveWindowMinutes,
            Optional<Clock> clock
    ) {
        this.enabled = enabled;
        this.maxTrackedKeys = Math.max(1, maxTrackedKeys);
        this.loginIpMaxRequests = loginIpMaxRequests;
        this.loginIpWindowMinutes = loginIpWindowMinutes;
        this.loginAccountMaxRequests = loginAccountMaxRequests;
        this.loginAccountWindowMinutes = loginAccountWindowMinutes;
        this.registerMaxRequests = registerMaxRequests;
        this.registerWindowMinutes = registerWindowMinutes;
        this.sensitiveMaxRequests = sensitiveMaxRequests;
        this.sensitiveWindowMinutes = sensitiveWindowMinutes;
        this.clock = (clock != null && clock.isPresent()) ? clock.get() : Clock.systemUTC();
    }

    public static RateLimitService createForTest(boolean enabled, int maxTrackedKeys, Clock clock) {
        return new RateLimitService(
                enabled,
                maxTrackedKeys,
                30, 15,
                10, 15,
                10, 60,
                5, 15,
                Optional.ofNullable(clock)
        );
    }

    public void check(String bucket, String key, int maxRequests, Duration window) {
        if (!enabled) {
            return;
        }
        if (bucket == null || key == null || maxRequests <= 0 || window == null || window.isZero() || window.isNegative()) {
            return;
        }

        String compositeKey = bucket + ":" + key;
        long now = clock.millis();
        long windowMillis = window.toMillis();
        long windowStart = (now / windowMillis) * windowMillis;
        long windowEnd = windowStart + windowMillis;

        AtomicInteger bucketCount = bucketKeyCounts.computeIfAbsent(bucket, b -> new AtomicInteger(0));

        WindowCounter existing = storage.get(compositeKey);
        WindowCounter counter;

        if (existing != null) {
            // Key is already tracked. Update counter atomically without consuming a new slot permit.
            counter = storage.compute(compositeKey, (k, current) -> {
                if (current == null) {
                    return null;
                }
                if (now >= current.windowEnd || current.windowStart != windowStart) {
                    return new WindowCounter(windowStart, windowEnd, 1);
                }
                return new WindowCounter(current.windowStart, current.windowEnd, current.count + 1);
            });
            if (counter == null) {
                // If it was purged in the split-second between get and compute, acquire slot through reservation
                counter = insertWithCapacityReservation(bucket, compositeKey, windowStart, windowEnd, windowMillis, now, bucketCount);
            }
        } else {
            // New key requires slot reservation under per-bucket capacity
            counter = insertWithCapacityReservation(bucket, compositeKey, windowStart, windowEnd, windowMillis, now, bucketCount);
        }

        if (counter.count > maxRequests) {
            long retryAfterSeconds = Math.max(1, (counter.windowEnd - now + 999) / 1000);
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }

    private WindowCounter insertWithCapacityReservation(
            String bucket,
            String compositeKey,
            long windowStart,
            long windowEnd,
            long windowMillis,
            long now,
            AtomicInteger bucketCount
    ) {
        // 1. If at or above capacity, purge expired entries for this bucket first
        if (bucketCount.get() >= maxTrackedKeys) {
            purgeExpiredEntries(bucket, bucketCount, now);
        }

        // 2. Atomically reserve a slot via CAS loop (fail closed if still full)
        while (true) {
            int current = bucketCount.get();
            if (current >= maxTrackedKeys) {
                long earliestEnd = findEarliestWindowEnd(bucket, now, windowEnd);
                long retryAfterSeconds = Math.max(1, (earliestEnd - now + 999) / 1000);
                logSaturationWarning(bucket, now, windowMillis);
                throw new RateLimitExceededException(retryAfterSeconds);
            }
            if (bucketCount.compareAndSet(current, current + 1)) {
                break;
            }
        }

        // 3. Insert new key or increment if inserted concurrently
        boolean[] newlyAdded = new boolean[1];
        WindowCounter counter = storage.compute(compositeKey, (k, existing) -> {
            if (existing == null || now >= existing.windowEnd || existing.windowStart != windowStart) {
                newlyAdded[0] = (existing == null);
                return new WindowCounter(windowStart, windowEnd, 1);
            }
            newlyAdded[0] = false;
            return new WindowCounter(existing.windowStart, existing.windowEnd, existing.count + 1);
        });

        // If another thread inserted this key concurrently, release our duplicate reservation
        if (!newlyAdded[0]) {
            bucketCount.updateAndGet(c -> Math.max(0, c - 1));
        }

        return counter;
    }

    private void purgeExpiredEntries(String bucket, AtomicInteger bucketCount, long now) {
        String prefix = bucket + ":";
        for (Map.Entry<String, WindowCounter> entry : storage.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                WindowCounter val = entry.getValue();
                if (now >= val.windowEnd) {
                    if (storage.remove(entry.getKey(), val)) {
                        bucketCount.updateAndGet(c -> Math.max(0, c - 1));
                    }
                }
            }
        }
    }

    private long findEarliestWindowEnd(String bucket, long now, long fallbackWindowEnd) {
        String prefix = bucket + ":";
        long earliest = Long.MAX_VALUE;
        for (Map.Entry<String, WindowCounter> entry : storage.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                long end = entry.getValue().windowEnd;
                if (end > now && end < earliest) {
                    earliest = end;
                }
            }
        }
        return earliest == Long.MAX_VALUE ? fallbackWindowEnd : earliest;
    }

    private void logSaturationWarning(String bucket, long now, long windowMillis) {
        AtomicLong lastWarn = bucketLastWarnTimes.computeIfAbsent(bucket, b -> new AtomicLong(0));
        long prev = lastWarn.get();
        if (now - prev >= windowMillis && lastWarn.compareAndSet(prev, now)) {
            log.warn("Rate limit bucket '{}' has reached key tracking capacity. Rejecting new keys until active windows expire.", bucket);
        }
    }

    public void checkLoginIp(String clientIp) {
        check(BUCKET_LOGIN_IP, clientIp, loginIpMaxRequests, Duration.ofMinutes(loginIpWindowMinutes));
    }

    /**
     * Checks rate limits for the login-account bucket, keyed by normalized email only.
     *
     * @param normalizedEmail normalized email of the account being logged into
     */
    public void checkLoginAccount(String normalizedEmail) {
        check(BUCKET_LOGIN_ACCOUNT, normalizedEmail, loginAccountMaxRequests, Duration.ofMinutes(loginAccountWindowMinutes));
    }

    public void checkRegister(String clientIp) {
        check(BUCKET_REGISTER, clientIp, registerMaxRequests, Duration.ofMinutes(registerWindowMinutes));
    }

    public void checkSensitive(String userId) {
        check(BUCKET_SENSITIVE, userId, sensitiveMaxRequests, Duration.ofMinutes(sensitiveWindowMinutes));
    }

    public void check(String bucket, String key) {
        switch (bucket) {
            case BUCKET_LOGIN_IP -> checkLoginIp(key);
            case BUCKET_LOGIN_ACCOUNT -> checkLoginAccount(key);
            case BUCKET_REGISTER -> checkRegister(key);
            case BUCKET_SENSITIVE -> checkSensitive(key);
            default -> throw new IllegalArgumentException("Unknown rate limit bucket: " + bucket);
        }
    }

    public int getTrackedKeyCount() {
        return storage.size();
    }

    public int getTrackedKeyCount(String bucket) {
        AtomicInteger count = bucketKeyCounts.get(bucket);
        return count != null ? count.get() : 0;
    }

    private static final class WindowCounter {
        final long windowStart;
        final long windowEnd;
        final int count;

        WindowCounter(long windowStart, long windowEnd, int count) {
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.count = count;
        }
    }
}

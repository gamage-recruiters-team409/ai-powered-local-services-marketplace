package com.gamagerecruiters.marketplace.modules.auth.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

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

        ensureCapacity(compositeKey, now);

        WindowCounter counter = storage.compute(compositeKey, (k, existing) -> {
            if (existing == null || now >= existing.windowEnd || existing.windowStart != windowStart) {
                return new WindowCounter(windowStart, windowEnd, 1);
            }
            return new WindowCounter(existing.windowStart, existing.windowEnd, existing.count + 1);
        });

        if (counter.count > maxRequests) {
            long retryAfterSeconds = Math.max(1, (counter.windowEnd - now + 999) / 1000);
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }

    public void checkLoginIp(String clientIp) {
        check(BUCKET_LOGIN_IP, clientIp, loginIpMaxRequests, Duration.ofMinutes(loginIpWindowMinutes));
    }

    public void checkLoginAccount(String ipAndEmailKey) {
        check(BUCKET_LOGIN_ACCOUNT, ipAndEmailKey, loginAccountMaxRequests, Duration.ofMinutes(loginAccountWindowMinutes));
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

    private synchronized void ensureCapacity(String compositeKey, long now) {
        if (storage.containsKey(compositeKey)) {
            return;
        }
        if (storage.size() >= maxTrackedKeys) {
            // 1. Purge expired entries first
            storage.entrySet().removeIf(entry -> now >= entry.getValue().windowEnd);

            // 2. If still full, purge oldest/arbitrary keys until under capacity
            while (storage.size() >= maxTrackedKeys) {
                Iterator<String> it = storage.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                } else {
                    break;
                }
            }
        }
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

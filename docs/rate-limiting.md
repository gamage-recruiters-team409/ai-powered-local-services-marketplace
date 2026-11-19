# Rate Limiting Architecture & Configuration

## 1. Overview & Protected Endpoints

The marketplace backend implements an in-memory fixed-window rate limiter (`RateLimitService`) designed to protect authentication and sensitive account endpoints against brute-force attacks, credential stuffing, and resource exhaustion.

### Configured Buckets & Defaults

| Bucket Name | Target Endpoints | Keyed By | Default Limit | Default Window | Configuration Properties |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `login-ip` | `POST /api/v1/auth/login` | Client IP (`request.getRemoteAddr()`) | 30 requests | 15 minutes | `app.rate-limit.login-ip.max-requests`<br>`app.rate-limit.login-ip.window-minutes` |
| `login-account` | `POST /api/v1/auth/login` | Normalized email (`trim().toLowerCase(Locale.ROOT)`) | 10 requests | 15 minutes | `app.rate-limit.login-account.max-requests`<br>`app.rate-limit.login-account.window-minutes` |
| `register` | `POST /api/v1/auth/register` | Client IP (`request.getRemoteAddr()`) | 10 requests | 60 minutes | `app.rate-limit.register.max-requests`<br>`app.rate-limit.register.window-minutes` |
| `sensitive` | `POST /api/v1/auth/change-password`<br>`PUT /api/v1/customers/me/password`<br>`POST /api/v1/customers/me/deactivate` | Authenticated user ID (`CurrentUser.getUserId()`) | 5 requests | 15 minutes | `app.rate-limit.sensitive.max-requests`<br>`app.rate-limit.sensitive.window-minutes` |
| `forgot-password-ip` | `POST /api/v1/auth/forgot-password` | Client IP (`request.getRemoteAddr()`) | 10 requests | 60 minutes | `app.rate-limit.forgot-password-ip.max-requests`<br>`app.rate-limit.forgot-password-ip.window-minutes` |
| `forgot-password-email` | `POST /api/v1/auth/forgot-password` | Normalized email (`trim().toLowerCase(Locale.ROOT)`) | 3 requests | 60 minutes | `app.rate-limit.forgot-password-email.max-requests`<br>`app.rate-limit.forgot-password-email.window-minutes` |
| `resend-verification-ip` | `POST /api/v1/auth/resend-verification` | Client IP (`request.getRemoteAddr()`) | 10 requests | 60 minutes | `app.rate-limit.resend-verification-ip.max-requests`<br>`app.rate-limit.resend-verification-ip.window-minutes` |
| `resend-verification-email` | `POST /api/v1/auth/resend-verification` | Normalized email (`trim().toLowerCase(Locale.ROOT)`) | 3 requests | 60 minutes | `app.rate-limit.resend-verification-email.max-requests`<br>`app.rate-limit.resend-verification-email.window-minutes` |
| `verify-email` | `POST /api/v1/auth/verify-email` | Client IP (`request.getRemoteAddr()`) | 30 requests | 15 minutes | `app.rate-limit.verify-email.max-requests`<br>`app.rate-limit.verify-email.window-minutes` |
| `reset-password` | `POST /api/v1/auth/reset-password` | Client IP (`request.getRemoteAddr()`) | 20 requests | 15 minutes | `app.rate-limit.reset-password.max-requests`<br>`app.rate-limit.reset-password.window-minutes` |

Master switch: `app.rate-limit.enabled=${RATE_LIMIT_ENABLED:true}`  
Key capacity per bucket: `app.rate-limit.max-tracked-keys=${RATE_LIMIT_MAX_TRACKED_KEYS:10000}`

---

## 2. Deployment Architecture & Single-Instance Assumption

> **Assumption (to be confirmed with Team Lead):**  
> The current architecture assumes a **single backend instance** deployment. The rate limiter maintains fixed windows in JVM memory (`ConcurrentHashMap` with atomic `compute` and CAS slot reservations).

If the backend is scaled horizontally across multiple instances in the future:
- Each instance maintains independent local counters, effectively multiplying the client quota by the number of nodes unless sticky sessions are configured.
- Production clustering with shared rate limits across multiple nodes will require a distributed coordination store, such as Redis (e.g., Redis sliding window / token bucket).

---

## 3. Client IP Resolution & Reverse Proxy Setup

Client IP address is resolved using `request.getRemoteAddr()` only. The raw `X-Forwarded-For` HTTP header is **not** trusted directly in application code, as callers can easily spoof this header to bypass IP-based quotas.

### Reverse Proxy Deployment Requirement
When deploying behind a reverse proxy (e.g., Nginx, AWS Application Load Balancer, Cloudflare):
1. The reverse proxy must be configured as the **only network route** to the backend application (direct ingress traffic to port 8080 must be firewalled).
2. Spring Boot's forward-headers strategy must be enabled in `application.properties`:
   ```properties
   server.forward-headers-strategy=framework
   # or server.forward-headers-strategy=native
   ```
3. The reverse proxy must strip untrusted `X-Forwarded-For` headers from public clients and set `X-Forwarded-For` and `X-Forwarded-Proto` securely.

*Warning:* Without proper forward-headers configuration behind a proxy, all client requests will appear to come from the proxy's IP address, causing all marketplace users to share one IP rate limit bucket.

---

## 4. Safe Saturation Policy & Memory Bounds

To prevent memory exhaustion attacks, `RateLimitService` enforces a strict, bounded memory model:

1. **Per-Bucket Capacity:**  
   The `app.rate-limit.max-tracked-keys` limit (default: 10,000) is enforced independently per bucket. Flooding the `login-account` bucket with thousands of random emails cannot saturate or block `register`, `login-ip`, or `sensitive` user endpoints.

2. **No Active Eviction:**  
   Active window counters (`now < windowEnd`) are **never** evicted. Only expired entries (`now >= windowEnd`) are purged when capacity thresholds are reached. This guarantees that an attacker cannot clear a blocked IP or account counter by inserting extraneous keys.

3. **Fail-Closed on Saturation:**  
   If a request introduces a brand-new key and the bucket is at capacity with no expired entries to purge, the service **fails closed**:
   - Throws `RateLimitExceededException` (HTTP 429 `RATE_LIMITED`).
   - Sets the `Retry-After` header to the number of seconds until the earliest active window in that bucket expires (minimum 1 second).
   - Does not insert the key.

4. **Continued Service for Existing Keys:**  
   Existing tracked keys continue to be evaluated and rate-limited normally during store saturation.

5. **Atomic Capacity Reservation:**  
   Slot reservations for new keys use an atomic CAS loop (`AtomicInteger.compareAndSet`), preventing check-then-insert race conditions under high concurrency.

6. **Log Sanitization:**  
   No sensitive identifiers (emails, raw keys, IP-with-email strings) are ever logged. Bucket saturation is logged at `WARN` level with rate-throttling (at most once per window per bucket).

---

## 5. Security Design & Trade-Offs

### Account-Level Rate Limiting
- The `login-account` bucket is keyed strictly by normalized email (`email.trim().toLowerCase(Locale.ROOT)`), independent of the client IP.
- This ensures that distributed credential stuffing attacks distributed across many IP addresses (botnets) are stopped once the threshold for that specific account is reached.

### Known Trade-Off: Account-Level Denial of Service
- Because login rate limits for an account do not depend on the IP, an attacker who knows a user's email address can intentionally trigger 10 invalid login attempts from any IP address to exhaust that account's login quota for 15 minutes.
- This trade-off is accepted to prevent password brute-forcing and credential stuffing against accounts. The user can log in once the window expires, and user passwords remain safe.

### Information Disclosure Prevention
- Both IP and account-level rate limit checks run **before** user database lookups.
- Returning HTTP 429 does not disclose whether the targeted email corresponds to a real registered user account.

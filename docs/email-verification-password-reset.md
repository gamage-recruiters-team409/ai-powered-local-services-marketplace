# Email Verification & Password Reset Architecture

## 1. Overview & Flow Diagrams

This document details the backend design, API specification, security policies, and token handling for **Email Verification** and **Password Reset** within WP01 (Authentication) for the AI-Powered Local Services Marketplace.

### Email Verification Flow

```
[ User / Client ]                   [ Backend AuthController ]               [ EmailTokenService ]               [ MongoDB / EmailSender ]
       |                                       |                                      |                                     |
       |-- POST /register -------------------->|                                      |                                     |
       |                                       |-- authService.register ------------->|                                     |
       |                                       |                                      |-- save user (PENDING_VERIFICATION)->|
       |                                       |                                      |-- generateOpaqueToken() ----------->|
       |                                       |                                      |-- save AuthToken (SHA-256 hash) --->|
       |                                       |                                      |-- sendEmail(ACCOUNT_VERIFICATION) ->|
       |<-- 201 Created (PENDING_VERIFICATION)-|                                      |                                     |
       |                                       |                                      |                                     |
       |-- (User opens link in email) -------->|                                      |                                     |
       |-- POST /verify-email {token} -------->|                                      |                                     |
       |                                       |-- checkVerifyEmail(clientIp) ------->|                                     |
       |                                       |-- verifyEmail(token) --------------->|                                     |
       |                                       |                                      |-- atomic consumeValidToken() ------>|
       |                                       |                                      |-- user.setStatus(ACTIVE) ---------->|
       |                                       |                                      |-- user.setEmailVerified(true) ----->|
       |<-- 200 OK {"message": "..."} ---------|                                      |                                     |
       |                                       |                                      |                                     |
       |-- POST /login ----------------------->|                                      |                                     |
       |<-- 200 OK (AuthResponse JWT) ---------|                                      |                                     |
```

### Password Reset Flow

```
[ User / Client ]                   [ Backend AuthController ]               [ EmailTokenService ]               [ MongoDB / EmailSender ]
       |                                       |                                      |                                     |
       |-- POST /forgot-password {email} ----->|                                      |                                     |
       |                                       |-- checkForgotPassword(ip, email) --->|                                     |
       |                                       |-- forgotPassword(email) ------------>|                                     |
       |                                       |                                      |-- lookup user (ACTIVE or LOCKED) -->|
       |                                       |                                      |-- invalidate old reset tokens ----->|
       |                                       |                                      |-- generate & hash opaque token ---->|
       |                                       |                                      |-- save AuthToken (SHA-256 hash) --->|
       |                                       |                                      |-- sendEmail(PASSWORD_RESET) ------->|
       |<-- 200 OK (Generic response) ---------|                                      |                                     |
       |                                       |                                      |                                     |
       |-- (User clicks link in email) ------->|                                      |                                     |
       |-- POST /reset-password {token, pass}->|                                      |                                     |
       |                                       |-- checkResetPassword(clientIp) ----->|                                     |
       |                                       |-- resetPassword(token, newPass) ---->|                                     |
       |                                       |                                      |-- validate password complexity ---->|
       |                                       |                                      |-- lookup & validate token/user ---->|
       |                                       |                                      |-- verify not SAME_PASSWORD -------->|
       |                                       |                                      |-- atomic consumeValidToken() ------>|
       |                                       |                                      |-- update passwordHash, status=ACTIVE|
       |                                       |                                      |-- clear failedLoginCount & lockout->|
       |                                       |                                      |-- revokeAllActiveTokens(REFRESH) -->|
       |                                       |                                      |-- revokeAllUnusedTokens(RESET) ---->|
       |<-- 200 OK {"message": "..."} ---------|                                      |                                     |
```

---

## 2. API Endpoints

All endpoints are public endpoints configured in `SecurityConfig.PUBLIC_ENDPOINTS` under `/api/v1/auth`.

### 1. `POST /api/v1/auth/verify-email`
Verifies user registration using an opaque token received via email link.
- **Request Body:**
  ```json
  {
    "token": "43-character-base64url-opaque-token"
  }
  ```
- **Validation:** `@NotBlank`, `@Size(max = 256)`.
- **Rate Limit:** Bucket `verify-email` (default 30 requests / 15 minutes per client IP).
- **Responses:**
  - `200 OK`: `{"message": "Email verified successfully"}`
  - `400 Bad Request`: `{"status": 400, "error": "INVALID_OR_EXPIRED_TOKEN", "message": "Invalid or expired token"}`
  - `429 Too Many Requests`: `{"status": 429, "error": "RATE_LIMITED", ...}` with `Retry-After` header.

### 2. `POST /api/v1/auth/resend-verification`
Triggers resending a verification link to an unverified user.
- **Request Body:**
  ```json
  {
    "email": "user@example.com"
  }
  ```
- **Validation:** `@NotBlank`, `@Email`.
- **Anti-Enumeration / Generic Response:** Returns the exact same `200 OK` message whether the email exists, does not exist, is already verified, or is subject to cooldown.
- **Rate Limit:**
  - IP bucket `resend-verification-ip` (default 10 requests / 60 minutes).
  - Account bucket `resend-verification-email` (default 3 requests / 60 minutes, keyed by normalized email).
- **Cooldown:** Requests within `AUTH_RESEND_VERIFICATION_WAIT_SECONDS` (default 60 seconds) of the last created token are silently ignored without sending duplicate emails.
- **Responses:**
  - `200 OK`: `{"message": "If an account needs verification, a new email has been sent"}`
  - `400 Bad Request`: `{"status": 400, "error": "VALIDATION_FAILED", ...}`
  - `429 Too Many Requests`: `{"status": 429, "error": "RATE_LIMITED", ...}`

### 3. `POST /api/v1/auth/forgot-password`
Initiates self-service password recovery for an account.
- **Request Body:**
  ```json
  {
    "email": "user@example.com"
  }
  ```
- **Validation:** `@NotBlank`, `@Email`.
- **Anti-Enumeration / Generic Response:** Returns `200 OK` regardless of whether the account exists, is active, or is ineligible.
- **Eligibility:**
  - Sent only if user exists and status is `ACTIVE` or `LOCKED`.
  - Silently ignored if user status is `SUSPENDED`, `DEACTIVATED`, or `PENDING_VERIFICATION` (prevents password reset bypassing email verification or administrative suspensions).
- **Rate Limit:**
  - IP bucket `forgot-password-ip` (default 10 requests / 60 minutes).
  - Account bucket `forgot-password-email` (default 3 requests / 60 minutes, keyed by normalized email).
- **Responses:**
  - `200 OK`: `{"message": "If an account exists for this email, a reset link has been sent"}`
  - `400 Bad Request`: `{"status": 400, "error": "VALIDATION_FAILED", ...}`
  - `429 Too Many Requests`: `{"status": 429, "error": "RATE_LIMITED", ...}`

### 4. `POST /api/v1/auth/reset-password`
Completes password recovery by consuming the reset token and setting a new password.
- **Request Body:**
  ```json
  {
    "token": "43-character-base64url-opaque-token",
    "newPassword": "NewStrongPassword123!"
  }
  ```
- **Validation:**
  - `token`: `@NotBlank`, `@Size(max = 256)`.
  - `newPassword`: `@NotBlank`, regex pattern `^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$` (min 8 characters, at least 1 uppercase, 1 lowercase, 1 digit).
- **Rate Limit:** Bucket `reset-password` (default 20 requests / 15 minutes per client IP).
- **Security Actions:**
  - Checks that `newPassword` is not equal to current password (`SAME_PASSWORD`).
  - Sets new BCrypt encoded password hash.
  - Clears account lockout: sets `failedLoginCount = 0`, `lockStartedAt = null`, and resets status to `ACTIVE` if status was `LOCKED`.
  - Clears `mustChangePassword = false`.
  - Session revocation: atomically revokes all active `REFRESH` tokens for the user, terminating all existing sessions.
  - Invalidates any other unused `PASSWORD_RESET` tokens for the user.
- **Responses:**
  - `200 OK`: `{"message": "Password reset successfully"}`
  - `400 Bad Request`:
    - `INVALID_OR_EXPIRED_TOKEN`: Token expired, invalid, or already consumed.
    - `SAME_PASSWORD`: New password matches the existing password.
    - `WEAK_PASSWORD`: New password does not meet password complexity requirements.
  - `429 Too Many Requests`: `{"status": 429, "error": "RATE_LIMITED", ...}`

---

## 3. Token Security & Storage Architecture

1. **Generation:**
   - Generated using 32 cryptographically secure random bytes via `SecureRandom`.
   - Encoded as a 43-character Base64 URL-safe string without padding (`java.util.Base64.getUrlEncoder().withoutPadding()`).

2. **Storage:**
   - Raw tokens are **never** persisted in the database.
   - The database stores only the SHA-256 hash (`TokenService.hashToken(rawToken)`).
   - If the database is compromised, stored hashes cannot be reversed to forge verification or reset links.

3. **Atomic Consumption & Consistency Strategies (Standalone MongoDB):**
   - The local Docker-compose MongoDB environment is standalone (no replica set transactions), so the authentication service employs failure-safe ordering and compensation strategies instead of `@Transactional`:
     - **Email Verification (Idempotent Activation):**
       `EmailTokenServiceImpl.verifyEmail` validates the token via `findValidToken(...)`, activates the user in the database, and only consumes the token (`consumeValidToken(...)`) after the user save succeeds. If saving the user fails, the token remains untouched and valid for retry. If a parallel request already consumed the token, the request still succeeds because the account has been verified.
     - **Password Reset (Fail-Secure Ordering & Compensation):**
       `EmailTokenServiceImpl.resetPassword` validates policy, account eligibility, and `SAME_PASSWORD` pre-checks before atomically claiming the token with `consumeValidToken(...)` (guaranteeing single-use under concurrency). Inside a guarded block (**Phase A**), it executes in fail-secure order:
       1. Revokes all active refresh tokens for the user first (if this fails, the password is unchanged and sessions remain intact).
       2. Encodes the new password, resets lockouts and `mustChangePassword`, and saves the user (if this fails, the user simply logs in again with the old credentials).
       3. If ANY exception occurs during Phase A (before the password is persisted), a compensation routine (`AuthTokenRepository.releaseConsumedToken`) atomically resets `usedAt` back to `null` (matching `usedAt != null` and `revokedAt == null`), allowing the same reset link to be reused until expiration.
       4. **Phase B (best-effort cleanup):** After the password is persisted, other unused password-reset tokens for the same user are revoked. This runs outside the compensation block; if it fails the error is logged at `WARN` level and swallowed. The consumed token is **never** released after the password is saved — the other unused links simply expire within 30 minutes.
     - **Residual Crash Window:**
       In the rare event of an unrecoverable JVM crash or hardware power loss during the milliseconds between the token claim and the user save, the link could remain consumed in the standalone database; in this residual case, the user simply requests a new reset link.

---

## 4. Email Delivery & Templates

Emails are dispatched through the `EmailSender` interface:
- **Development & Test (`DevLoggingEmailSender`):**
  - Enabled when `app.email.provider=dev` (default) under profiles `local`, `dev`, `test`, and `integration-test`.
  - Logs masked recipient (e.g. `u***@example.com`) without emitting credentials or sending real network packets.
  - Enforces required template keys:
    - `ACCOUNT_VERIFICATION`: requires `name`, `verificationLink`, `expiresInMinutes` (Integer).
    - `PASSWORD_RESET`: requires `name`, `resetLink`, `expiresInMinutes` (Integer).
- **Error Handling:**
  - Email sending failures or exceptions are logged at `WARN` level with `userId` only.
  - Service methods catch email errors so client registration or forgot-password API calls do not fail if the mail server is temporarily unavailable.

---

## 5. Configuration & Environment Variables

| Property | Default | Environment Variable | Purpose |
| :--- | :--- | :--- | :--- |
| `app.auth.require-email-verification` | `true` | `REQUIRE_EMAIL_VERIFICATION` | Enforce email verification before login. Bypass (`false`) is permitted only in `local`, `dev`, `test`. |
| `app.auth.email-verification-expiry-minutes` | `1440` | `AUTH_EMAIL_VERIFICATION_EXPIRY_MINUTES` | Email verification token lifespan (default 24 hours). |
| `app.auth.password-reset-expiry-minutes` | `30` | `AUTH_PASSWORD_RESET_EXPIRY_MINUTES` | Password reset token lifespan (default 30 minutes). |
| `app.auth.resend-verification-wait-seconds` | `60` | `AUTH_RESEND_VERIFICATION_WAIT_SECONDS` | Cooldown period between resend verification requests. |
| `app.frontend.base-url` | `http://localhost:5173` | `FRONTEND_BASE_URL` | Base URL used to construct links in email templates. |
| `app.rate-limit.forgot-password-ip.max-requests` | `10` | `RATE_LIMIT_FORGOT_PASSWORD_IP_MAX_REQUESTS` | Forgot password IP rate limit. |
| `app.rate-limit.forgot-password-ip.window-minutes` | `60` | `RATE_LIMIT_FORGOT_PASSWORD_IP_WINDOW_MINUTES` | Forgot password IP window. |
| `app.rate-limit.forgot-password-email.max-requests` | `3` | `RATE_LIMIT_FORGOT_PASSWORD_EMAIL_MAX_REQUESTS` | Forgot password account rate limit. |
| `app.rate-limit.forgot-password-email.window-minutes` | `60` | `RATE_LIMIT_FORGOT_PASSWORD_EMAIL_WINDOW_MINUTES` | Forgot password account window. |
| `app.rate-limit.resend-verification-ip.max-requests` | `10` | `RATE_LIMIT_RESEND_VERIFICATION_IP_MAX_REQUESTS` | Resend verification IP rate limit. |
| `app.rate-limit.resend-verification-ip.window-minutes` | `60` | `RATE_LIMIT_RESEND_VERIFICATION_IP_WINDOW_MINUTES` | Resend verification IP window. |
| `app.rate-limit.resend-verification-email.max-requests` | `3` | `RATE_LIMIT_RESEND_VERIFICATION_EMAIL_MAX_REQUESTS` | Resend verification account rate limit. |
| `app.rate-limit.resend-verification-email.window-minutes` | `60` | `RATE_LIMIT_RESEND_VERIFICATION_EMAIL_WINDOW_MINUTES` | Resend verification account window. |
| `app.rate-limit.verify-email.max-requests` | `30` | `RATE_LIMIT_VERIFY_EMAIL_MAX_REQUESTS` | Verify email rate limit. |
| `app.rate-limit.verify-email.window-minutes` | `15` | `RATE_LIMIT_VERIFY_EMAIL_WINDOW_MINUTES` | Verify email window. |
| `app.rate-limit.reset-password.max-requests` | `20` | `RATE_LIMIT_RESET_PASSWORD_MAX_REQUESTS` | Reset password rate limit. |
| `app.rate-limit.reset-password.window-minutes` | `15` | `RATE_LIMIT_RESET_PASSWORD_WINDOW_MINUTES` | Reset password window. |

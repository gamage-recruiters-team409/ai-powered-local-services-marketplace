# WP01 Authentication Frontend Documentation

## Overview

This document describes the implementation of the frontend authentication module for the AI-Powered Local Services Marketplace (WP01). Built using React 19, Vite, Axios, and React Router DOM, the authentication module provides complete coverage of user authentication, onboarding, email verification, and password recovery, aligning with high-fidelity visual design specifications.

---

## 1. Visual Design & Design System Alignment

The user interface implements the 15 high-fidelity visual designs matching the LocalServe design system:
- **Brand Palette:** LocalServe teal accent (`#0F766E` / `teal-700`), mint highlights (`#CCFBF1`), and neutral slate grays.
- **Typography:** Inter typography hierarchy with explicit label sizing, helper text, and validation feedback.
- **Form Controls:** Shared UI form components (`Input`, `PasswordInput`, `Button`, `Checkbox`, `Alert`, `Spinner`).
- **Responsive Layout:** Responsive down to 360px viewport widths with responsive grids and padding.

---

## 2. Architecture & Token Lifecycle

### 2.1 In-Memory Access Tokens & Persistent Refresh Tokens (`tokenStore.js`)
- **Access Tokens:** Maintained exclusively in module-scoped memory variables. Access tokens are never written to `localStorage`, `sessionStorage`, cookies, or URL query parameters, protecting against cross-site scripting (XSS) extraction.
- **Refresh Tokens:** Stored in `localStorage` under the key `auth_refresh_token`. All storage operations are safely wrapped in `try...catch` blocks to support private/incognito browsing modes and restricted iframe environments.
- **Token Clearing:** `tokenStore.clear()` invalidates in-memory access tokens and cleans up storage.

### 2.2 Shared API Client & Refresh Coordination (`apiClient.js`)
- **Request Interceptor:** Automatically appends `Authorization: Bearer <token>` to requests while excluding public authentication endpoints (`/auth/login`, `/auth/register`, `/auth/refresh`, `/auth/verify-email`, `/auth/resend-verification`, `/auth/forgot-password`, `/auth/reset-password`).
- **Single-Flight Refresh Queue:** Parallel 401 errors share a single in-flight refresh promise, preventing redundant token rotations.
- **Cross-Tab Web Lock Coordination:** Utilizes `navigator.locks.request('auth_token_refresh')` where supported to coordinate rotation across multiple browser tabs.
- **Concurrency & Backoff Handling:** When encountering HTTP 409 `REFRESH_IN_PROGRESS`, the client applies a 400ms delay and re-evaluates the token state before retrying.
- **Error Normalization:** Standardizes all API error responses into `{ status, code, message, fieldErrors, retryAfterSeconds }`.

### 2.3 Cross-Tab Synchronization (`AuthContext.jsx`)
- Broadcasts authentication events across browser tabs using the browser `BroadcastChannel('auth')`:
  - `login`: Synchronizes authenticated user state across tabs.
  - `logout`: Ensures immediate session termination across all open windows.
  - `token-updated`: Synchronizes rotated tokens across active tabs.

---

## 3. Routes & Guards

### Routes
| Route | Component | Guard | Description |
|---|---|---|---|
| `/login` | `LoginPage` | `GuestOnly` | Email & password login, Google OAuth stub, account lockout and unverified email handling. |
| `/register` | `RegisterPage` | `GuestOnly` | Customer/Provider role selection, Sri Lankan phone input (+94), live password rules, terms checkbox. |
| `/check-email` | `CheckEmailPage` | Public | Verification email confirmation with 60s cooldown timer persisted across page reloads. |
| `/verify-email` | `VerifyEmailPage` | Public | Automatic token consumption, immediate URL cleaning, StrictMode double-execution protection. |
| `/forgot-password` | `ForgotPasswordPage` | `GuestOnly` | Password recovery initiation with generic success confirmation. |
| `/reset-password` | `ResetPasswordPage` | `GuestOnly` | New password submission with 4-criteria checklist and 30-minute validity indicator. |
| `/change-password` | `ForceChangePasswordPage` | `RequireAuth` | Mandatory password update for users flagged with `mustChangePassword=true`. |

### Route Guards
- `RequireAuth`: Verifies authentication status, preserving target paths in `location.state.from`.
- `RequireRole`: Enforces role-based access control (e.g. `['ADMIN', 'SUPER_ADMIN']`).
- `GuestOnly`: Redirects already authenticated users to their corresponding dashboard (`/admin` or `/`).
- `RequirePasswordChanged`: Forces users requiring password reset to complete `/change-password`.

---

## 4. Feature Integration & Backward Compatibility

- **Disputes Admin (`authBridge.js`):** Updated to delegate `getAccessToken()`, `hasSession()`, and `endSession()` directly to the shared `tokenStore`, while preserving developer fallback in `import.meta.env.DEV`.
- **Application Router (`router.jsx`):** Mounted `authRoutes` seamlessly without modifying the root path `/` or conflicting with existing tests.
- **Application Provider (`App.jsx`):** Root provider hierarchy wrapped with `<AuthProvider>`.

---

## 5. Security Measures

1. **Token Sanitization:** Tokens in URL query parameters (`?token=...`) are stripped immediately upon mount using `window.history.replaceState`.
2. **Referrer Protection:** Token handling pages dynamically attach `<meta name="referrer" content="no-referrer">` while mounted to prevent token leakage via outgoing HTTP Referer headers.
3. **No Sensitive Data Logging:** Passwords, tokens, and raw credentials are never logged to console or telemetry.
4. **React 19 StrictMode Guard:** Uses idempotency refs to prevent double-firing single-use tokens during component mounting.

---

## 6. WebSocket Authentication

- **Handshake Subprotocol Format:** Browsers cannot send custom `Authorization` headers during WebSocket handshakes. The client connects specifying the subprotocol array:
  ```javascript
  const ws = new WebSocket(wsUrl, ["bearer", accessToken]);
  ```
- **Server Subprotocol Echo:** The server handshake interceptor validates the subprotocol and echoes `"bearer"` back to the client upon successful validation.
- **Connect-Time Access Token Validation:** Handshake interceptors call `AuthService.validateAccessToken(token)` from the auth module to validate the access token. Validation occurs once at connect time; clients refresh tokens via HTTP and reconnect after token expiry.
- **Handshake Route Security:** `SecurityConfig` allows unauthenticated `GET /ws` handshakes through the security filter chain, delegating authentication to the handshake interceptor. Non-GET methods and subpaths remain strictly protected.

---

## 7. Admin User Management & Analytics (WP09 Integration)

### 7.1 New User Registration Analytics (`countNewUsers`)
- **Signature:** `List<PeriodCount> countNewUsers(String actorId, Set<Role> roles, Instant from, Instant to, Granularity granularity)`
- **Aggregation Pipeline:** Executes a single MongoDB aggregation (`$match` -> `$group` via `$dateTrunc` -> `$sort` by `_id`) grouping by week (`monday` start of week) or month in UTC.
- **Empty Period Zero-Filling:** All periods within `[from, to)` are generated and empty periods are guaranteed to be present with `count: 0`, ordered chronologically.
- **Actor Binding & Scoping:** Strict validation identical to `searchUsers`: requires valid authenticated session matching `actorId`, active actor account, and restricts queries to the actor's permitted role hierarchy (`AdminRolePolicy.visibleRoles`).
- **Legacy Records Handling:** Users lacking a `createdAt` field (or where `createdAt` is null) are excluded from period counts by the `$gte: from` date criteria. No additional indexes are introduced.

### 7.2 User Activity Tracking (`lastActiveAt`)
- **Entity Field:** `private Instant lastActiveAt` on `User.java`.
- **Update Frequency:** Updated **strictly** on successful password login (`AuthServiceImpl.login`) and on successful token refresh (`AuthServiceImpl.refresh`). It is **intentionally NOT updated on every API request** to avoid high-frequency write contention and database write amplification.
- **Summary Exposure:** Exposed via `AdminUserSummary.lastActiveAt()`.
- **Backward Compatibility:** Preserves an overloaded 7-argument constructor on `AdminUserSummary` setting `lastActiveAt = null` to maintain binary and source compatibility for existing services and tests.

### 7.3 Atomic Optimistic Status Modification (`changeStatus`)
- **Signature:** `User changeStatus(String actorId, String userId, UserStatus expectedStatus, UserStatus newStatus)`
- **Atomic Conditional Update:** Executes `mongoTemplate.updateFirst(query, update, User.class)` where the query matches both `{ _id: userId, status: expectedStatus }`.
- **Conflict Handling:** If `matchedCount == 0`, verifies whether the user exists in the database. If present, throws HTTP 409 `CONCURRENT_MODIFICATION` indicating concurrent state modification; if absent, throws HTTP 404 `USER_NOT_FOUND`.
- **Policy Enforcement:** Reuses `AdminRolePolicy.validateStatusChange(actor, target, newStatus, userRepository)` to verify active actor status, disallow self-modification, enforce administrative role hierarchies, disallow modifying `DEACTIVATED` accounts, and protect the last active `SUPER_ADMIN`.
- **Session Revocation:** Automatically revokes all active `REFRESH` tokens for the target user via `AuthTokenRepository.revokeAllActiveTokensByUserIdAndType(userId, TokenType.REFRESH)` whenever `newStatus` is `SUSPENDED` or `DEACTIVATED`. When transitioned to `ACTIVE`, resets `failedLoginCount` to 0 and unsets `lockStartedAt`.

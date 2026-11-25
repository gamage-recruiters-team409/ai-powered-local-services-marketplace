# Customer Profile Photo & Payments Documentation (WP01)

## 1. Overview
This document covers the customer profile photo storage and payments page field enhancements delivered under WP01 on branch `feature/customer-photo-listing-adapter-payments`.

---

## 2. Customer Profile Photo Endpoints

Base path: `/api/v1/customers/me`  
All endpoints require authentication with the `CUSTOMER` role (`hasRole('CUSTOMER')`).

### 2.1. Upload Profile Photo
- **Method & Path:** `PUT /api/v1/customers/me/photo`
- **Content-Type:** `multipart/form-data`
- **Request Part:** `file` (MultipartFile)
- **Supported Formats:** JPEG (`image/jpeg`, `image/jpg`) and PNG (`image/png`) only
- **Max File Size:** 5 MB (enforced by `application.properties` multipart limits and storage validation)
- **Security & Integrity Checks:**
  - Content-Type check against allowed image MIME types.
  - Magic byte verification: validates initial file bytes (JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`) to prevent file extension/header spoofing.
  - Active customer verification (`UserStatus.ACTIVE`).
- **Response Status:** `200 OK`
- **Response Body (`CustomerProfileResponse`):**
  ```json
  {
    "id": "c-123",
    "fullName": "Kamal Perera",
    "email": "kamal@example.lk",
    "phone": "+94771234567",
    "role": "CUSTOMER",
    "status": "ACTIVE",
    "emailVerified": true,
    "profileImageUrl": "https://res.cloudinary.com/demo/image/upload/v1234567890/localserve/customer-profile/c-123/uuid.jpg"
  }
  ```
  *(Note: `profileImagePublicId` is strictly internal and never exposed in any client-facing response or DTO).*
- **Error Codes:**
  - `400 EMPTY_FILE`: file is missing or has zero bytes.
  - `400 FILE_TOO_LARGE` / `413 PAYLOAD_TOO_LARGE`: file exceeds 5 MB.
  - `400 INVALID_FILE_TYPE`: unsupported file format (e.g. GIF, WebP, PDF).
  - `400 INVALID_FILE_SIGNATURE`: file contents do not match declared image signature.
  - `401 UNAUTHORIZED`: unauthenticated request.
  - `403 ACCESS_DENIED` / `ACCOUNT_INACTIVE`: non-customer role or suspended/deactivated account.
  - `502 STORAGE_ERROR`: Cloudinary upload failed or returned incomplete response.
  - `503 PROFILE_PHOTO_UPDATE_FAILED`: Database update failed after upload (orphaned image is cleaned up automatically).

### 2.2. Delete Profile Photo
- **Method & Path:** `DELETE /api/v1/customers/me/photo`
- **Response Status:** `204 No Content`
- **Behavior:**
  - Unsets `profileImageUrl` and `profileImagePublicId` on the User document using targeted MongoDB `$unset`.
  - Best-effort deletion of the previous photo from Cloudinary storage (`destroy` with `invalidate: true`).
  - Idempotent: returns `204` cleanly even if the user had no existing profile photo.

---

## 3. Order of Operations, Concurrency & Durable Cleanup

### 3.1. Optimistic Concurrency Control & Fail-Safe Cleanup Scheduling (F1, F4, F2-A, F2-B)
To prevent concurrent requests from clobbering newer photos or creating orphaned Cloudinary images:
1. **Initial Read:** The service reads the user document and records `expectedOldPublicId` (`null` if no photo exists).
2. **Upload to Cloudinary:** The new image Y is uploaded.
3. **Required Pre-Schedule Cleanup:** If `expectedOldPublicId` (X) is present, a cleanup task (`ProfileImageCleanupReason.REPLACED`) is scheduled in MongoDB (`profile_image_cleanup`) in `PENDING` status before executing the DB write.
   - **Fail-Safe Requirement (F2-B):** `schedule(X)` must verifiably persist. If scheduling throws `ProfileImageCleanupScheduleException`:
     * The user document is NOT mutated (reference X stays).
     * Newly uploaded image Y is deleted directly via storage port (best effort).
     * If direct deletion fails, an ERROR log is emitted with `ownerUserId` and `publicId` (class names only).
     * Throws `503 PROFILE_PHOTO_UPDATE_FAILED`.
4. **Conditional MongoDB Update:**
   ```java
   Criteria.where("_id").is(customerId)
       .and("role").is(Role.CUSTOMER)
       .and("status").is(UserStatus.ACTIVE)
       .and("profileImagePublicId").is(expectedOldPublicId)
   ```
   *(Note: `Criteria.is(null)` matches both null and missing fields in MongoDB).*
5. **Update Verification (`matchedCount`):**
   - **`matchedCount == 1` (Success):** DB update succeeded. Attempt deletion of `expectedOldPublicId` directly via `ProfileImageCleanupService.attempt(oldCleanupTask)`. (F2-A: No in-process post-update re-assert schedule call needed; pre-written persisted task guarantees durability).
   - **`matchedCount == 0` (Conflict/State Change):**
     * Definite non-application: newly uploaded image Y is rolled back via `schedule(Y, CONFLICT_ROLLBACK)` + `attempt(Y)`.
     * If scheduling fails, falls back to direct best-effort delete of Y (unreferenced and request-private). If that fails, logs ERROR with `ownerUserId` + `publicId`.
     * Pre-existing cleanup tasks for `expectedOldPublicId` are NEVER cancelled (F4: losing requests never cancel cleanup tasks).
     * Reload user: if status is inactive, throw `403 ACCOUNT_INACTIVE`. If `profileImagePublicId != expectedOldPublicId`, throw `409 PROFILE_PHOTO_CONFLICT`.
6. **Exception on DB Operation (Uncertain Outcome Handling - F1, F2-B):**
   - When `mongoTemplate.updateFirst` throws an exception, the write outcome is treated as UNCERTAIN.
   - Perform a targeted verification read for the user's `profileImagePublicId`:
     * **Read fails (outcome unknown):** Attempt to schedule a durable `PENDING` cleanup task for Y (`UPLOAD_ROLLBACK`). If scheduling also fails, do NOT delete Y (never delete an image with unknown reference state); log ERROR with `ownerUserId` + `publicId`; throw `503 PROFILE_PHOTO_UPDATE_FAILED`.
     * **`current == new publicId`:** The write actually committed. Treat as SUCCESS: do not delete the new image, run normal replace follow-up (attempt cleanup of old photo), and return success response.
     * **`current != new publicId`:** Y is provably unreferenced: try `schedule(Y)` + `attempt(rollbackTask)`. Fall back to direct delete of Y if scheduling throws (logs ERROR if direct delete fails). Throw `503 PROFILE_PHOTO_UPDATE_FAILED`.

### 3.2. Deletion Concurrency (`deleteProfilePhoto`)
1. Read user: if inactive, throw `403 ACCOUNT_INACTIVE`.
2. If `expectedPublicId` is null/blank, return `204 No Content` immediately.
3. **Required Pre-Schedule Cleanup (F2-B):** Schedule cleanup task (`ProfileImageCleanupReason.REMOVED`). If `schedule(...)` throws, do NOT mutate the user document; return `503 PROFILE_PHOTO_UPDATE_FAILED`.
4. Execute conditional `$unset` matching `_id`, `role == CUSTOMER`, `status == ACTIVE`, and `profileImagePublicId == expectedPublicId`.
5. If `matchedCount == 1`: execute inline deletion attempt via `ProfileImageCleanupService.attempt(...)` without any post-update re-assert schedule call.
6. If `matchedCount == 0`: reload user.
   - If photo is already null (concurrent remove succeeded): return `204` idempotently.
   - If photo changed to another image: throw `409 PROFILE_PHOTO_CONFLICT`. (No cancel calls).
7. If an exception occurs on DB unset: log warning and throw `503 PROFILE_PHOTO_DELETE_FAILED` without cancelling cleanup tasks.

### 3.3. Durable Cleanup Collection (`profile_image_cleanup`) & Background Job (F2, F3, F2-A, F2-B)
To guarantee that Cloudinary failures or process crashes never result in permanently orphaned files:
- **Collection:** `profile_image_cleanup`
- **Fields:**
  - `id`: Unique task identifier.
  - `publicId`: Cloudinary public_id (indexed unique).
  - `ownerUserId`: Customer ID owning the image.
  - `reason`: `REPLACED`, `REMOVED`, `UPLOAD_ROLLBACK`, `CONFLICT_ROLLBACK`.
  - `status`: `PENDING`, `DONE`.
  - `attempts`: Number of failed Cloudinary deletion attempts (starts at 0; incremented ONLY on actual storage failure).
  - `createdAt`: Timestamp when task was originally created.
  - `scheduledAt`: Timestamp refreshed on every `schedule(...)` call and initialized to `now` on reopen.
  - `lastAttemptAt`: Timestamp of latest attempt.
  - `lastErrorClass`: Simple class name of last error (never logs tokens, URLs, or secrets).
  - `nextAttemptAt`: Timestamp for next eligible attempt.
  - `leaseUntil`: Distributed lease expiration timestamp for multi-instance safety.
- **Indexes:**
  - `publicId` (unique)
  - `(status, nextAttemptAt)` compound index for efficient task claiming.
- **Still-Referenced Lifecycle (F2-A):**
  A still-referenced image is NEVER marked `DONE` immediately. It stays `PENDING` and is deferred:
  - `nextAttemptAt = now + referencedDefer` (default `PT5M`).
  - Lease is released (`leaseUntil = null`).
  - `lastErrorClass = "ReferencedByUser"`.
  - `attempts` count is NOT incremented (claiming does not increase attempts; only real storage failures do).
  - **Bounded Resolution:** If `now - scheduledAt > referencedStaleAfter` (default `PT6H`), the task transitions to `DONE` with `lastErrorClass = "ReferencedStale"`, preventing permanent retries.
  - Startup validation enforces `referenced-stale-after >= PT10M` and `referenced-defer >= PT1M`.
- **Multi-Instance Lease Claiming:**
  Both `attempt(...)` and the background recovery job use atomic `findAndModify` to claim tasks:
  `findAndModify(status=PENDING && nextAttemptAt<=now && (leaseUntil is null || leaseUntil<now)) -> set leaseUntil=now+lease`.
  Expired leases allow crashed instances' tasks to be claimed by surviving nodes.
- **Atomic Scheduling (`schedule`):**
  Uses `findAndModify` with `upsert=true`, `$setOnInsert`, and `$set("scheduledAt", now)`. Catches `DuplicateKeyException` to safely re-read the existing document. Throws `ProfileImageCleanupScheduleException` on failure (never returns an unpersisted task). If the task is `DONE`, it re-opens it to `PENDING` (`status=PENDING`, `scheduledAt=now`, `nextAttemptAt=now`, unset `leaseUntil`).
- **Production Recovery Job (`ProfileImageCleanupJob`):**
  - Enabled via `@Profile("!test")` and `@ConditionalOnProperty("app.customer.profile-image-cleanup.enabled")`.
  - Runs with fixed delay (`PT5M`, default) and initial delay (`PT1M`, default).
  - Processes up to `batch-size` (default 25) pending tasks per execution.
  - Enabled through `ProfileImageCleanupSchedulingConfig` (`@EnableScheduling`).

---

## 4. Payments Page Field Mapping & Contract (F4)

### 4.1. Customer Payment Contract (`PaymentResponse`)
Customer payments are queried via `GET /api/v1/payments/me` returning `PaymentPageResponse` containing `List<PaymentResponse>`.

| JSON Field | Java Type | Source in `Payment` Entity | Nullable | Customer UI Rendering |
| :--- | :--- | :--- | :--- | :--- |
| `paymentId` | `String` | `payment.getId()` | No | Unique payment identifier |
| `bookingId` | `String` | `payment.getBookingId()` | No | Associated booking identifier |
| `amount` | `BigDecimal` | `payment.getAmount()` | No | Formatted currency amount (e.g. `2,500.00 LKR`) |
| `currency` | `String` | `payment.getCurrency()` | No | Currency symbol/code (defaults to `LKR`) |
| `status` | `PaymentStatus` | `payment.getStatus()` | No | Primary payment status badge (`CAPTURED`, `INITIATED`, etc.) |
| `refundedAmount`| `BigDecimal` | `payment.getRefundedAmount()` | Yes | Displays refunded portion (e.g. "Refunded 1,000 of 5,000 LKR") |
| `createdAt` | `Instant` | `payment.getCreatedAt()` | No | Creation timestamp |
| `paidAt` | `Instant` | `payment.getPaidAt()` | Yes | Formatted payment date (`DD MMM YYYY`) or `'—'` |
| `receiptReference` | `String` | `payment.getReceiptReference()` | Yes | Text receipt ID or `'—'`, with disabled receipt action |
| `refundReference` | `String` | `payment.getRefundReference()` | Yes | Refund tracking ID or `'—'`, with disabled details action |
| `refundStatus` | `String` | `payment.getRefundStatus()` | Yes | Badge mapped via `refundStatus.js` (see mapping table below) |

*Internal fields (`gatewayRef`, `commissionRate`, `commissionAmount`, `updatedAt`) are strictly excluded from `PaymentResponse` and never exposed.*

### 4.2. Refund Status Mapping (`refundStatus.js`)
WP07 and payment workflow states are mapped to customer-facing badge styles:

| Backend / WP07 State | Display Label | Badge Variant | Note |
| :--- | :--- | :--- | :--- |
| `UNDER_REVIEW` | Under review | `warning` | Standard review status |
| `REQUIRES_RECONCILIATION` | Under review | `warning` | WP07 internal state mapping |
| `PROCESSING` | Processing | `info` | Standard in-progress refund |
| `PENDING` | Processing | `info` | WP07 pending refund movement |
| `COMPLETED` | Completed | `success` | Standard completed refund |
| `SUCCEEDED` | Completed | `success` | WP07 succeeded status alias |
| `FAILED` | Failed | `danger` | Refund failure |
| `REJECTED` | Failed | `danger` | Refund dispute rejection |
| `CANCELLED` | Cancelled | `muted` | Cancelled refund request |
| *(Any Unknown String)* | Title Cased String | `muted` | Robust fallback (e.g. `ESCALATED_TO_SUPPORT` -> "Escalated To Support") |
| `null` / Absent | Fallback to `status` | Varies | Uses `status === 'REFUND_PENDING'` or `PARTIALLY_REFUNDED / REFUNDED` |

---

## 5. Configuration & Environment Variables

### 5.1. Backend (`application.properties`)
```properties
# Multipart File Upload Limits (Global servlet limit allows up to 10MB verification documents)
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=11MB

# Customer Profile Image Background Cleanup Job
app.customer.profile-image-cleanup.enabled=true
app.customer.profile-image-cleanup.interval=PT5M
app.customer.profile-image-cleanup.initial-delay=PT1M
app.customer.profile-image-cleanup.batch-size=25
app.customer.profile-image-cleanup.lease=PT2M
app.customer.profile-image-cleanup.referenced-defer=PT5M
app.customer.profile-image-cleanup.referenced-stale-after=PT6H
```
*(Customer profile photos remain strictly capped at 5 MB by `CloudinaryCustomerProfileImageStorage`).*

### 5.2. Cloudinary Credentials (`backend/.env`)
Required environment variables for real Cloudinary integration:
```env
CLOUDINARY_CLOUD_NAME=your_cloud_name
CLOUDINARY_API_KEY=your_api_key
CLOUDINARY_API_SECRET=your_api_secret
```
*(Never commit real credentials to Git).*

---

## 6. Pending Items & Cross-Team Dependencies

1. **Recent Bookings Section:**
   - Pending WP02 customer booking list endpoint (`GET /api/v1/bookings/me`). Once WP02 provides this API, the Recent Bookings widget can be connected without altering payments or profile functionality.
2. **Direct Receipt and Refund Links:**
   - `View receipt` and `View refund details` buttons remain disabled with tooltip `"Not available yet"`, pending WP07 endpoints for downloadable receipts and refund itemization.

---

## 7. Manual Testing Procedure

1. **Profile Photo Upload / Delete:**
   - Sign in as a customer at `/login`.
   - Navigate to `/account/profile`.
   - Observe circular avatar with user initials (e.g. "NP").
   - Click "Change photo" and select a valid JPEG or PNG file (< 5 MB).
   - Observe local preview, uploading indicator, and success toast: "Profile photo updated successfully".
   - Refresh the page and confirm the profile image persists from the server.
   - Click "Remove photo" and confirm the photo is deleted, avatar reverts to initials, and toast confirms "Profile photo removed".
   - Try uploading a `.gif` or `.pdf` file to confirm client-side validation rejection.
2. **Payments & Refunds Display:**
   - Navigate to `/account/payments`.
   - Verify Date column displays formatted `paidAt` date or `'—'`.
   - Verify Receipt column displays `receiptReference` or `'—'`.
   - Verify Refund column displays correct refund status badges and refund references.

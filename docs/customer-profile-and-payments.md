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

## 3. Order of Operations for Photo Storage

1. **Upload Flow:**
   1. Validate user status is `ACTIVE`.
   2. Validate file size (<= 5 MB), Content-Type, and magic bytes.
   3. Upload new photo to Cloudinary under folder `localserve/customer-profile/<sanitized_userId>/<random_uuid>`.
   4. Execute targeted MongoDB update (`$set: { profileImageUrl, profileImagePublicId }`) on `users` collection.
   5. If MongoDB update fails, immediately execute best-effort cleanup of the new photo from Cloudinary and throw `503 PROFILE_PHOTO_UPDATE_FAILED`.
   6. On DB success, execute best-effort deletion of the **old** image from Cloudinary (if previously had one). Old deletion failures do not fail the request.
   7. Reload and return updated `CustomerProfileResponse`.

2. **Delete Flow:**
   1. Validate user status is `ACTIVE`.
   2. If user has no existing `profileImagePublicId`, return `204` immediately.
   3. Execute targeted MongoDB update (`$unset: { profileImageUrl, profileImagePublicId }`).
   4. Execute best-effort deletion of the old image from Cloudinary.

---

## 4. Payments Page Field Mapping

Under `/account/payments`, the payments table displays customer payment and refund history:
- **Date Column:** Shows `paidAt` formatted as `DD MMM YYYY` (e.g. `12 Oct 2026`). If `paidAt` is null (e.g. pending/failed payments), displays `'—'`.
- **Receipt Cell:** Displays `receiptReference` as text (or `'—'` if null). Retains the disabled `View receipt` button (`data-testid="Link_ViewReceipt"` with `title="Not available yet"`).
- **Refund Cell:**
  - Badges mapped via `refundStatus.js`:
    - `UNDER_REVIEW` -> "Under review" (warning)
    - `PROCESSING` -> "Processing" (info)
    - `COMPLETED` -> "Completed" (success)
    - `FAILED` -> "Failed" (danger)
    - Unknown/custom status -> Title-cased label with neutral muted badge
  - Displays `refundReference` when present.
  - Displays refunded amount details (e.g. "Refunded 3,000 of 9,500 LKR" or "Requested 12,000 LKR").
  - Retains the disabled `View refund details >` button (`data-testid="Link_ViewRefundDetails"` with `title="Not available yet"`).

---

## 5. Configuration & Environment Variables

### 5.1. Backend (`application.properties`)
```properties
# Multipart File Upload Limits
spring.servlet.multipart.max-file-size=5MB
spring.servlet.multipart.max-request-size=6MB
```

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

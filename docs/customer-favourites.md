# Customer Favourites (FR-CUST-005)

## 1. Overview
The Favourites feature enables authenticated customers to save and manage their favourite service providers and service listings. Favourites are persisted in the `customer_favourites` collection and are accessible under `/api/v1/customers/me/favorites`.

---

## 2. Endpoints Contract

All endpoints require authentication with the `CUSTOMER` role (`hasRole('CUSTOMER')`).

### 2.1. List Favourites (Paginated)
- **Method & Path:** `GET /api/v1/customers/me/favorites`
- **Query Parameters:**
  - `type` (required): `PROVIDER` or `LISTING`
  - `page` (optional, default `0`): zero-based page number
  - `size` (optional, default `6`, maximum `50`): items per page
- **Response Status:** `200 OK`
- **Response Body (`CustomerFavouritePageResponse`):**
  ```json
  {
    "content": [
      {
        "favouriteId": "654321abcdef...",
        "type": "PROVIDER",
        "targetId": "prov-123",
        "name": "Nuwan Perera",
        "subtitle": null,
        "category": null,
        "city": "Colombo",
        "imageUrl": "https://cdn.example.lk/profiles/nuwan.jpg",
        "rating": null,
        "reviewCount": null,
        "priceFrom": null,
        "priceUnit": null,
        "currency": null,
        "inactive": false,
        "savedAt": "2026-11-19T03:00:00Z"
      }
    ],
    "page": 0,
    "size": 6,
    "totalElements": 1,
    "totalPages": 1
  }
  ```
- **Error Codes:**
  - `400 INVALID_FAVOURITE_TYPE`: if `type` query parameter is missing or not `PROVIDER` / `LISTING`.
  - `401 UNAUTHORIZED`: if unauthenticated.
  - `403 ACCESS_DENIED`: if authenticated user is not a `CUSTOMER`.
  - `503 FAVOURITES_LISTING_UNAVAILABLE`: if requesting `LISTING` when the listing module adapter is unavailable.

### 2.2. Add Favourite (Idempotent)
- **Method & Path:** `PUT /api/v1/customers/me/favorites/{type}/{targetId}`
- **Path Variables:**
  - `type`: `PROVIDER` or `LISTING`
  - `targetId`: Unique identifier of the provider or listing
- **Response Status:** `200 OK`
- **Response Body (`CustomerFavouriteResponse`):**
  ```json
  {
    "favouriteId": "654321abcdef...",
    "type": "PROVIDER",
    "targetId": "prov-123",
    "name": "Nuwan Perera",
    "subtitle": null,
    "category": null,
    "city": "Colombo",
    "imageUrl": "https://cdn.example.lk/profiles/nuwan.jpg",
    "rating": null,
    "reviewCount": null,
    "priceFrom": null,
    "priceUnit": null,
    "currency": null,
    "inactive": false,
    "savedAt": "2026-11-19T03:00:00Z"
  }
  ```
- **Behavior:**
  - Idempotent: If the target is already saved, returns `200 OK` with the item without creating a duplicate.
  - Concurrent duplicate submissions are protected by MongoDB unique index and caught cleanly.
- **Error Codes:**
  - `400 INVALID_FAVOURITE_TYPE`: if `type` is invalid.
  - `400 INVALID_TARGET_ID`: if `targetId` is blank.
  - `404 TARGET_NOT_FOUND`: if the target provider or listing does not exist.
  - `503 FAVOURITES_LISTING_UNAVAILABLE`: if adding a `LISTING` when the listing module adapter is unavailable.

### 2.3. Remove Favourite (Idempotent)
- **Method & Path:** `DELETE /api/v1/customers/me/favorites/{type}/{targetId}`
- **Path Variables:**
  - `type`: `PROVIDER` or `LISTING`
  - `targetId`: Unique identifier of the provider or listing
- **Response Status:** `204 No Content`
- **Behavior:**
  - Idempotent: Returns `204 No Content` even if the target was not currently saved.

---

## 3. MongoDB Collection & Indexes

- **Collection:** `customer_favourites`
- **Document Structure:**
  ```json
  {
    "_id": ObjectId("..."),
    "customerId": "cust-uuid",
    "type": "PROVIDER",
    "targetId": "prov-uuid",
    "createdAt": ISODate("2026-11-19T03:00:00Z")
  }
  ```
- **Unique Index:**
  - Name: `customerId_1_type_1_targetId_1_unique`
  - Key: `{ "customerId": 1, "type": 1, "targetId": 1 }`
  - Unique: `true`
  - Managed by `CustomerFavouriteIndexInitializer` (non-destructive; creates missing collection and index, pre-checks duplicates, never drops).

---

## 4. Inactive and Missing Target Rules

1. **Missing Targets:**
   - If a saved favourite target has been deleted from the database, it is **silently omitted** from the returned `content` list rather than failing the whole request.
2. **Suspended Providers / Paused Listings:**
   - Providers with `SUSPENDED` status (or any non-active user status) are marked `inactive: true`.
   - Paused or archived listings are marked `inactive: true`.
   - Inactive items remain visible in the customer's favourites list with disabled action buttons.
3. **Privacy Protection:**
   - Sensitive provider information (email, phone, address line) is strictly excluded from favourite target responses.

---

## 5. Listing Module Dependency & Fail-Closed Behavior

The listing module (WP03, Sahan's work) introduces `ListingService.getListingsByIds(Collection<String>)`.
- **Current State:** The listing module is not yet merged (`modules/listing` only contains `.gitkeep`).
- **Fail-Closed Behavior:** No adapter is registered for `FavouriteListingLookup`. The service injects `ObjectProvider<FavouriteListingLookup>`. When the lookup is null, any request for `LISTING` favourites returns `503 Service Unavailable` with error code `FAVOURITES_LISTING_UNAVAILABLE`.
- **Provider Favourites Unaffected:** Provider favourites (`type=PROVIDER`) continue to work normally via `FavouriteProviderLookupAdapter`.

### How to Enable the Listing Adapter Once WP03 is Merged:
1. Implement `FavouriteListingLookup` in an adapter class:
   ```java
   @Component
   public class FavouriteListingLookupAdapter implements FavouriteListingLookup {
       private final ListingService listingService;
       // delegate lookup and lookupBatch to listingService.getListingsByIds(...)
   }
   ```
2. Map listing fields:
   - `name`: listing title
   - `subtitle`: provider display name
   - `category`: service category name
   - `city`: service city
   - `imageUrl`: primary service image
   - `rating`, `reviewCount`, `priceFrom`, `priceUnit`, `currency`: from listing DTO
   - `inactive`: true if listing status is `PAUSED`, `ARCHIVED`, or if the provider is suspended.
3. Spring will automatically detect the `@Component` bean and `listingLookupProvider.getIfAvailable()` will return the adapter, enabling listing favourites without touching service logic.

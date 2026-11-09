# Migration: users.phone Partial Unique Index

**Date:** 2026-11-09  
**Author:** WP01 Auth Team  
**Collection:** `users`  
**Field:** `phone`

## Background

The auth module requires a **partial unique index** on `users.phone` so that:
- Phone numbers are unique among users who have one set (non-null string).
- Users without a phone (null/absent) are excluded from the uniqueness constraint,
  allowing multiple admin accounts with no phone.

An earlier version of the codebase may have created a **non-partial unique index** on
`users.phone`, which blocks inserting multiple documents with `phone: null`. The
application now **refuses to start** if it detects this legacy index, directing you here.

## Pre-Checks

1. Connect to MongoDB Atlas (or your local instance) using `mongosh`.
2. **Do NOT include connection strings in scripts or logs.**
3. Inspect current indexes:

   ```js
   use marketplace;
   db.users.getIndexes();
   ```

4. Look for an index with key `{ phone: 1 }`.
   - If it has **no** `partialFilterExpression` → legacy index, proceed with migration.
   - If it already has `partialFilterExpression: { phone: { $type: "string" } }` →
     migration already applied, no action needed.
   - If no phone index exists at all → the application will create it automatically on
     next startup.

## Migration Steps

Run the script in `docs/db-migrations/users-phone-partial-index.js`:

```bash
mongosh "your-connection-string" docs/db-migrations/users-phone-partial-index.js
```

Or execute the steps manually:

### Step 1: Check for duplicate phone numbers

```js
db.users.aggregate([
  { $match: { phone: { $type: "string" } } },
  { $group: { _id: "$phone", count: { $sum: 1 } } },
  { $match: { count: { $gt: 1 } } }
]);
```

If duplicates exist, resolve them before proceeding.

### Step 2: Drop the legacy non-partial index

```js
db.users.dropIndex("phone_1");
```

### Step 3: Create the partial unique index

```js
db.users.createIndex(
  { phone: 1 },
  {
    unique: true,
    name: "phone_1",
    partialFilterExpression: { phone: { $type: "string" } }
  }
);
```

### Step 4: Verify

```js
db.users.getIndexes();
```

Confirm the `phone_1` index now has `partialFilterExpression: { phone: { $type: "string" } }`.

## Rollback

If you need to revert to the legacy index (not recommended):

```js
db.users.dropIndex("phone_1");
db.users.createIndex({ phone: 1 }, { unique: true, name: "phone_1" });
```

> **Warning:** Reverting will prevent the application from starting and will break
> admin account creation when multiple admins have no phone.

## Notes

- The migration script (`users-phone-partial-index.js`) is fully idempotent.
- If the Atlas cluster was already updated by a prior application run, the script
  will detect the correct index and skip all destructive steps.
- The application's `MongoIndexInitializer` **never drops indexes** — it only creates
  missing indexes and validates existing ones.

# Runbook: Customer Address Slot Allocation Migration

## Overview
To enforce the maximum address limit (default: 10) atomically under concurrent create requests without relying on multi-document transactions (which are unavailable on standalone MongoDB instances), each customer's saved addresses are assigned a distinct integer slot (`1..maxAddresses`). A partial unique index on `{ customerId: 1, slot: 1 }` with filter `{ slot: { $gt: 0 } }` enforces uniqueness at the database level.

This migration runbook explains how to backfill existing unslotted addresses and verify data integrity prior to or alongside application deployment.

---

## Pre-requisites & Pre-Checks

> **IMPORTANT:** In PowerShell, run:
> ```powershell
> mongosh $env:MONGODB_URI docs/db-migrations/precheck-customer-addresses.js
> ```
> against Atlas before the first startup of this branch; if it reports any rows, do not auto-fix data, and contact the Team Lead immediately.

1. **Verify Existing Indexes:**
   Run the read-only index inspection script in PowerShell:
   ```powershell
   mongosh $env:MONGODB_URI docs/db-migrations/inspect-indexes.js
   ```

2. **Count Unslotted Documents:**
   Run the following query in `mongosh` to check how many address records currently lack a slot:
   ```javascript
   use marketplace;
   db.customer_addresses.countDocuments({
     $or: [
       { slot: { $exists: false } },
       { slot: null }
     ]
   });
   ```

3. **Check for Slot Conflicts (Read-Only):**
   ```javascript
   use marketplace;
   db.customer_addresses.aggregate([
     { $match: { slot: { $gt: 0 } } },
     { $group: { _id: { customerId: "$customerId", slot: "$slot" }, count: { $sum: 1 } } },
     { $match: { count: { $gt: 1 } } }
   ]);
   ```
   If this returns any records, resolve conflicting slot values manually before proceeding.

---

## Migration Execution

Execute the idempotent backfill script in PowerShell:
```powershell
mongosh $env:MONGODB_URI docs/db-migrations/backfill-address-slots.js
```

### Script Actions:
- Scans `customer_addresses` for documents where `slot` is missing or `null`.
- Sorts unslotted addresses chronologically by `createdAt ASC`.
- Assigns the lowest available free integer slot in `1..10` to each document.
- If a customer already holds $\ge 10$ addresses, excess documents remain unslotted to prevent arbitrary deletions; new address creations for that customer are rejected by the API until addresses are removed.

---

## Verification

1. **Confirm All Eligible Addresses Have Slots:**
   ```javascript
   use marketplace;
   db.customer_addresses.countDocuments({
     $or: [
       { slot: { $exists: false } },
       { slot: null }
     ]
   });
   ```
   (Should return `0`, unless any customer had $> 10$ legacy addresses).

2. **Confirm Slot Unique Index Exists:**
   ```javascript
   use marketplace;
   db.customer_addresses.getIndexes();
   ```
   Verify that `customerId_1_slot_1_unique` is present with:
   - Key: `{ customerId: 1, slot: 1 }`
   - `unique: true`
   - `partialFilterExpression: { slot: { $gt: 0 } }`

---

## Rollback Procedure
If the migration must be rolled back:
```javascript
use marketplace;
// Unset slot field from all customer addresses
db.customer_addresses.updateMany(
  { slot: { $exists: true } },
  { $unset: { slot: "" } }
);
```
*(Note: Application version with slot enforcement must be rolled back before removing the slot fields).*

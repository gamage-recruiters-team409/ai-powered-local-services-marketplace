# Runbook: Customer Default Address Verification & Remediation

## Background
MongoDB enforces at most ONE default address per customer using a partial unique index on `customer_addresses`:
- **Key Pattern:** `{ customerId: 1, isDefault: 1 }`
- **Unique:** `true`
- **Partial Filter Expression:** `{ isDefault: true }`
- **Index Name:** `customerId_1_isDefault_1_unique_default`

During application startup, `CustomerIndexInitializer` validates this index. If the database already contains duplicate default addresses for any customer, startup fails immediately and safely with an `IllegalStateException` without dropping or altering any data.

---

## Read-Only Pre-Check Query
> **IMPORTANT:** In PowerShell, run:
> ```powershell
> mongosh $env:MONGODB_URI docs/db-migrations/precheck-customer-addresses.js
> ```
> against Atlas before the first startup of this branch; if it reports any rows, do not auto-fix data, and contact the Team Lead immediately.

To check whether any customer currently has more than 1 default address, run the following read-only aggregation in `mongosh`:

```javascript
// Run against the 'marketplace' database
db.getSiblingDB("marketplace").customer_addresses.aggregate([
  { $match: { isDefault: true } },
  { $group: { _id: "$customerId", count: { $sum: 1 }, addressIds: { $push: "$_id" } } },
  { $match: { count: { $gt: 1 } } }
]);
```

- If this returns **empty**, no duplicate default addresses exist; startup can proceed normally.
- If documents are returned, each document identifies a `customerId` with its count of default addresses and their IDs.

---

## Manual Remediation Procedure
If duplicates are found for a customer, determine which address should remain default (typically the most recently updated one), and unset the `isDefault` flag on the other addresses:

```javascript
// Example: Keep the most recently updated address as default, unset others:
const customerId = "TARGET_CUSTOMER_ID";
const defaults = db.getSiblingDB("marketplace").customer_addresses
  .find({ customerId: customerId, isDefault: true })
  .sort({ updatedAt: -1 })
  .toArray();

if (defaults.length > 1) {
  // Keep defaults[0], unset defaults[1..n]
  for (let i = 1; i < defaults.length; i++) {
    db.getSiblingDB("marketplace").customer_addresses.updateOne(
      { _id: defaults[i]._id },
      { $set: { isDefault: false, updatedAt: new Date() } }
    );
    print("Unset default on address " + defaults[i]._id);
  }
}
```

Once all customers have at most 1 default address, restart the application. The initializer will create the partial unique index successfully.

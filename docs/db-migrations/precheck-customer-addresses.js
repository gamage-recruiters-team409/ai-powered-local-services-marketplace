// ===========================================================================
// Pre-Check Customer Addresses (Read-Only)
// Purpose: Validates existing customer_addresses collection against business
//          constraints before deploying branch feature/customer-account-backend:
//          1. Identifies customers with more than one default address (isDefault: true)
//          2. Identifies customers holding more addresses than max allowed (default 10)
//          3. Identifies duplicate slot values for the same customer
//
// Usage: mongosh "$MONGODB_URI" docs/db-migrations/precheck-customer-addresses.js
//
// SAFETY: Read-only. Does not modify or drop data. NEVER prints credentials.
// ===========================================================================

const DB_NAME = "marketplace";
db = db.getSiblingDB(DB_NAME);

const maxAddressesEnv = typeof process !== "undefined" && process.env ? process.env.MAX_ADDRESSES : null;
const MAX_ADDRESSES = maxAddressesEnv ? parseInt(maxAddressesEnv, 10) : 10;

print("=== STARTING READ-ONLY PRE-CHECK FOR customer_addresses ===");
print("Target Collection: " + collection.getName());
print("Configured Max Addresses: " + MAX_ADDRESSES);
print("");

let issueCount = 0;

// Check 1: Multiple default addresses per customer
print("--- [Check 1] Checking for multiple default addresses (isDefault: true)... ---");
const multipleDefaults = collection.aggregate([
    { $match: { isDefault: true } },
    { $group: { _id: "$customerId", count: { $sum: 1 }, addressIds: { $push: "$_id" } } },
    { $match: { count: { $gt: 1 } } }
]).toArray();

if (multipleDefaults.length > 0) {
    issueCount += multipleDefaults.length;
    print("[FAIL] Found " + multipleDefaults.length + " customer(s) with multiple default addresses:");
    multipleDefaults.forEach(row => {
        print("  - CustomerId: " + row._id + ", Default Address Count: " + row.count + ", Address IDs: " + JSON.stringify(row.addressIds));
    });
} else {
    print("[PASS] No customers have multiple default addresses.");
}
print("");

// Check 2: Addresses exceeding max limit per customer
print("--- [Check 2] Checking for customers exceeding max address limit (" + MAX_ADDRESSES + ")... ---");
const overLimitCustomers = collection.aggregate([
    { $group: { _id: "$customerId", count: { $sum: 1 } } },
    { $match: { count: { $gt: MAX_ADDRESSES } } }
]).toArray();

if (overLimitCustomers.length > 0) {
    issueCount += overLimitCustomers.length;
    print("[WARN] Found " + overLimitCustomers.length + " customer(s) with more than " + MAX_ADDRESSES + " addresses:");
    overLimitCustomers.forEach(row => {
        print("  - CustomerId: " + row._id + ", Total Addresses: " + row.count);
    });
} else {
    print("[PASS] No customers exceed the address limit.");
}
print("");

// Check 3: Duplicate slot values per customer
print("--- [Check 3] Checking for duplicate slot values per customer... ---");
const duplicateSlots = collection.aggregate([
    { $match: { slot: { $gt: 0 } } },
    { $group: { _id: { customerId: "$customerId", slot: "$slot" }, count: { $sum: 1 }, addressIds: { $push: "$_id" } } },
    { $match: { count: { $gt: 1 } } }
]).toArray();

if (duplicateSlots.length > 0) {
    issueCount += duplicateSlots.length;
    print("[FAIL] Found " + duplicateSlots.length + " duplicate slot assignments:");
    duplicateSlots.forEach(row => {
        print("  - CustomerId: " + row._id.customerId + ", Slot: " + row._id.slot + ", Count: " + row.count);
    });
} else {
    print("[PASS] No duplicate slot assignments found.");
}
print("");

print("============================================================");
if (issueCount === 0) {
    print("[RESULT: PASS] All pre-checks passed successfully. Collection is ready for startup.");
} else {
    print("[RESULT: ACTION REQUIRED] Pre-check identified " + issueCount + " issues.");
    print("DO NOT auto-fix data at startup. Contact the Team Lead before starting the service.");
}
print("============================================================");

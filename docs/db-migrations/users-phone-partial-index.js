// ===========================================================================
// Migration: users.phone Partial Unique Index
// Date: 2026-11-09
// Usage: mongosh "your-connection-string" users-phone-partial-index.js
//
// This script is IDEMPOTENT — safe to run multiple times.
// It does NOT contain connection strings. Pass yours on the command line.
// ===========================================================================

const DB_NAME = "marketplace";
const COLLECTION = "users";
const INDEX_NAME = "phone_1";
const EXPECTED_FILTER = { phone: { $type: "string" } };

db = db.getSiblingDB(DB_NAME);
const coll = db.getCollection(COLLECTION);

print("=== users.phone Partial Unique Index Migration ===");
print("");

// Step 1: Inspect existing indexes
const indexes = coll.getIndexes();
let legacyFound = false;
let correctFound = false;

for (const idx of indexes) {
    if (idx.key && idx.key.phone !== undefined && Object.keys(idx.key).length === 1) {
        if (idx.partialFilterExpression &&
            JSON.stringify(idx.partialFilterExpression) === JSON.stringify(EXPECTED_FILTER)) {
            print("[OK] Correct partial unique index '" + idx.name + "' already exists.");
            correctFound = true;
        } else if (!idx.partialFilterExpression) {
            print("[WARN] Legacy non-partial index '" + idx.name + "' found. Will drop and recreate.");
            legacyFound = true;
        } else {
            print("[WARN] Unexpected partialFilterExpression on '" + idx.name + "': " +
                  JSON.stringify(idx.partialFilterExpression));
        }
    }
}

if (correctFound) {
    print("");
    print("[DONE] Migration already applied. No changes needed.");
    quit(0);
}

// Step 2: Check for duplicate phone numbers among string-typed values
print("");
print("Checking for duplicate phone numbers...");
const dupes = coll.aggregate([
    { $match: { phone: { $type: "string" } } },
    { $group: { _id: "$phone", count: { $sum: 1 } } },
    { $match: { count: { $gt: 1 } } }
]).toArray();

if (dupes.length > 0) {
    print("[ERROR] Duplicate phone numbers found! Resolve before migrating:");
    for (const d of dupes) {
        print("  phone: " + d._id + " (count: " + d.count + ")");
    }
    quit(1);
}
print("[OK] No duplicate phone numbers.");

// Step 3: Drop legacy index if present
if (legacyFound) {
    print("");
    print("Dropping legacy index '" + INDEX_NAME + "'...");
    coll.dropIndex(INDEX_NAME);
    print("[OK] Legacy index dropped.");
}

// Step 4: Create partial unique index
print("");
print("Creating partial unique index '" + INDEX_NAME + "'...");
coll.createIndex(
    { phone: 1 },
    {
        unique: true,
        name: INDEX_NAME,
        partialFilterExpression: EXPECTED_FILTER
    }
);
print("[OK] Partial unique index created.");

// Step 5: Verify
print("");
print("Verifying indexes...");
const finalIndexes = coll.getIndexes();
let verified = false;
for (const idx of finalIndexes) {
    if (idx.name === INDEX_NAME && idx.partialFilterExpression) {
        print("[OK] Index '" + INDEX_NAME + "' verified with partialFilterExpression.");
        verified = true;
    }
}

if (!verified) {
    print("[ERROR] Verification failed! Index '" + INDEX_NAME + "' not found or missing partialFilterExpression.");
    quit(1);
}

print("");
print("[DONE] Migration completed successfully.");

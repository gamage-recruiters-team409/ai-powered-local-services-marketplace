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

function isPhoneKeyValid(key) {
    return key && Object.keys(key).length === 1 && (key.phone === 1 || key.phone === 1.0);
}

function isUniqueTrue(unique) {
    return unique === true || unique === 1;
}

function isFilterValid(filter) {
    return filter && JSON.stringify(filter) === JSON.stringify(EXPECTED_FILTER);
}

// Step 1: Inspect existing indexes
const indexes = coll.getIndexes();
let legacyFound = false;
let legacyIndexName = INDEX_NAME;
let correctFound = false;

for (const idx of indexes) {
    if (idx.key && idx.key.phone !== undefined) {
        const validKey = isPhoneKeyValid(idx.key);
        const validUnique = isUniqueTrue(idx.unique);
        const validFilter = isFilterValid(idx.partialFilterExpression);

        if (validKey && validUnique && validFilter) {
            print("[OK] Correct partial unique index '" + idx.name + "' already exists.");
            correctFound = true;
        } else if (validKey && !idx.partialFilterExpression) {
            print("[WARN] Legacy non-partial index '" + idx.name + "' found (unique=" + idx.unique + "). Will drop and recreate.");
            legacyFound = true;
            legacyIndexName = idx.name;
        } else {
            print("[ERROR] Unrecognized incompatible index on users.phone detected: " + JSON.stringify(idx));
            print("Aborting. Manual review required: see docs/db-migrations/2026-11-users-phone-partial-index.md");
            quit(1);
        }
    }
}

if (correctFound && !legacyFound) {
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
    print("Dropping legacy index '" + legacyIndexName + "'...");
    coll.dropIndex(legacyIndexName);
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

// Step 5: Verify strict match (key, unique: true, exact filter)
print("");
print("Verifying indexes...");
const finalIndexes = coll.getIndexes();
let verified = false;
for (const idx of finalIndexes) {
    if (idx.key && idx.key.phone !== undefined) {
        if (isPhoneKeyValid(idx.key) && isUniqueTrue(idx.unique) && isFilterValid(idx.partialFilterExpression)) {
            print("[OK] Index '" + idx.name + "' strictly verified with key {phone: 1}, unique: true, and partialFilterExpression.");
            verified = true;
        }
    }
}

if (!verified) {
    print("[ERROR] Verification failed! Partial unique index with required specifications not found.");
    quit(1);
}

print("");
print("[DONE] Migration completed successfully.");

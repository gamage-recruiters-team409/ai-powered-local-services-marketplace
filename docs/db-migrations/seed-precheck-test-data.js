// ===========================================================================
// Seed Pre-Check Test Data (Test Utility)
// Purpose: Populates a scratch database with synthetic test scenarios to verify
//          that precheck-customer-addresses.js detects all target issues:
//          - Check 1: Multiple defaults
//          - Check 2: Addresses exceeding max limit
//          - Check 3: Duplicate slots
//          - Check 4: Unslotted addresses (informational)
//          - Clean valid customer
//
// SAFETY GUARD: Refuses to run unless MONGODB_DATABASE begins with "precheck_scratch"
//               to prevent accidental execution against production or dev data.
//
// Usage:
//   $env:MONGODB_DATABASE="precheck_scratch"
//   mongosh $env:MONGODB_URI docs/db-migrations/seed-precheck-test-data.js
// ===========================================================================

const DB_NAME = typeof process !== "undefined" && process.env && process.env.MONGODB_DATABASE
    ? process.env.MONGODB_DATABASE
    : "";

if (!DB_NAME || !DB_NAME.startsWith("precheck_scratch")) {
    print("=====================================================================");
    print("[FATAL] Safety Guard: seed-precheck-test-data.js can ONLY run against a");
    print("scratch database starting with 'precheck_scratch'.");
    print("Current MONGODB_DATABASE: '" + DB_NAME + "'");
    print("Set $env:MONGODB_DATABASE=\"precheck_scratch\" before running this script.");
    print("=====================================================================");
    quit(1);
}

const targetDb = db.getSiblingDB(DB_NAME);
const collection = targetDb.getCollection("customer_addresses");

print("=== SEEDING PRE-CHECK TEST DATA IN SCRATCH DATABASE ===");
print("Database: " + DB_NAME);
print("Collection: " + collection.getName());

// Clean slate for scratch test
collection.drop();
print("[INFO] Dropped existing scratch collection for clean test run.");

const now = new Date();
const documentsToInsert = [];

// 1. Customer with multiple default addresses (Check 1 violation)
const custMultipleDefaults = "cust-test-multiple-defaults";
documentsToInsert.push({
    customerId: custMultipleDefaults,
    label: "Home",
    addressLine1: "123 Default St",
    city: "Colombo",
    district: "Colombo",
    postalCode: "00100",
    isDefault: true,
    slot: 1,
    createdAt: now,
    updatedAt: now
});
documentsToInsert.push({
    customerId: custMultipleDefaults,
    label: "Office",
    addressLine1: "456 Duplicate Default Ave",
    city: "Colombo",
    district: "Colombo",
    postalCode: "00200",
    isDefault: true,
    slot: 2,
    createdAt: new Date(now.getTime() + 1000),
    updatedAt: new Date(now.getTime() + 1000)
});

// 2. Customer exceeding max address limit (Check 2 violation: 12 addresses)
const custOverLimit = "cust-test-over-limit";
for (let i = 1; i <= 12; i++) {
    documentsToInsert.push({
        customerId: custOverLimit,
        label: "Location " + i,
        addressLine1: "Address " + i + " St",
        city: "Colombo",
        district: "Colombo",
        postalCode: "00300",
        isDefault: (i === 1),
        slot: i <= 10 ? i : null,
        createdAt: new Date(now.getTime() + i * 1000),
        updatedAt: new Date(now.getTime() + i * 1000)
    });
}

// 3. Customer with duplicate slots (Check 3 violation: two addresses with slot 1)
const custDuplicateSlots = "cust-test-duplicate-slots";
documentsToInsert.push({
    customerId: custDuplicateSlots,
    label: "Warehouse A",
    addressLine1: "10 Harbor Rd",
    city: "Colombo",
    district: "Colombo",
    postalCode: "00400",
    isDefault: true,
    slot: 1,
    createdAt: now,
    updatedAt: now
});
documentsToInsert.push({
    customerId: custDuplicateSlots,
    label: "Warehouse B",
    addressLine1: "20 Harbor Rd",
    city: "Colombo",
    district: "Colombo",
    postalCode: "00400",
    isDefault: false,
    slot: 1, // Duplicate slot!
    createdAt: new Date(now.getTime() + 1000),
    updatedAt: new Date(now.getTime() + 1000)
});

// 4. Customer with unslotted addresses (Check 4 informational)
const custUnslotted = "cust-test-unslotted";
documentsToInsert.push({
    customerId: custUnslotted,
    label: "Old Legacy 1",
    addressLine1: "77 Heritage Ln",
    city: "Kandy",
    district: "Kandy",
    postalCode: "20000",
    isDefault: true,
    createdAt: now,
    updatedAt: now
});
documentsToInsert.push({
    customerId: custUnslotted,
    label: "Old Legacy 2",
    addressLine1: "78 Heritage Ln",
    city: "Kandy",
    district: "Kandy",
    postalCode: "20000",
    isDefault: false,
    slot: null,
    createdAt: new Date(now.getTime() + 1000),
    updatedAt: new Date(now.getTime() + 1000)
});

// 5. Customer with clean, valid data (should trigger no violations)
const custValid = "cust-test-valid-clean";
documentsToInsert.push({
    customerId: custValid,
    label: "Valid Home",
    addressLine1: "55 Clean Way",
    city: "Galle",
    district: "Galle",
    postalCode: "80000",
    isDefault: true,
    slot: 1,
    createdAt: now,
    updatedAt: now
});
documentsToInsert.push({
    customerId: custValid,
    label: "Valid Office",
    addressLine1: "56 Clean Way",
    city: "Galle",
    district: "Galle",
    postalCode: "80000",
    isDefault: false,
    slot: 2,
    createdAt: new Date(now.getTime() + 1000),
    updatedAt: new Date(now.getTime() + 1000)
});

const result = collection.insertMany(documentsToInsert);
print("[OK] Inserted " + result.insertedIds.length + " test documents across 5 test scenarios:");
print("  1. " + custMultipleDefaults + ": 2 default addresses (triggers Check 1)");
print("  2. " + custOverLimit + ": 12 total addresses (triggers Check 2)");
print("  3. " + custDuplicateSlots + ": 2 addresses with slot=1 (triggers Check 3)");
print("  4. " + custUnslotted + ": 2 unslotted addresses (triggers Check 4 info)");
print("  5. " + custValid + ": 2 valid addresses, 1 default, unique slots (clean)");
print("");
print("=== SEEDING COMPLETED SUCCESSFULLY ===");
print("Next run precheck against this scratch database:");
print("  mongosh $env:MONGODB_URI docs/db-migrations/precheck-customer-addresses.js");

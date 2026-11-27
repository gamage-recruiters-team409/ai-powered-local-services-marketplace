// ===========================================================================
// Pre-Check User Phone Numbers (Read-Only)
// Purpose: Validates existing users collection phone numbers against business
//          constraints before deploying branch fix/full-name-and-input-validation:
//          1. Counts users with phone not null
//          2. Counts users with canonical phone (+94XXXXXXXXX)
//          3. Counts users with non-canonical phone formats
//          4. Detects collision groups where multiple historical users normalize
//             to the exact same canonical +94 Sri Lankan phone number.
//
// Usage: mongosh "$MONGODB_URI" docs/db-migrations/precheck-user-phones.js
//
// SAFETY: Read-only. Does not modify or drop data. NEVER prints credentials.
// PRIVACY: Prints ONLY aggregate counts and collision group sizes.
//          NEVER prints phone numbers, emails, names, or user IDs.
// ===========================================================================

const DB_NAME = typeof process !== "undefined" && process.env && process.env.MONGODB_DATABASE
    ? process.env.MONGODB_DATABASE
    : "marketplace";
const targetDb = db.getSiblingDB(DB_NAME);

print("=== STARTING READ-ONLY PRE-CHECK FOR users PHONE NUMBERS ===");
print("Database: " + DB_NAME);
print("");

const collectionNames = targetDb.getCollectionNames();
if (!collectionNames.includes("users")) {
    print("[INFO] Collection 'users' does not exist in database '" + DB_NAME + "'.");
    print("[INFO] Fresh database detected. No existing user documents to evaluate.");
    print("");
    print("============================================================");
    print("PRE-CHECK RESULT: PASSED");
    print("============================================================");
    quit(0);
}

const usersCollection = targetDb.getCollection("users");
print("Target Collection: " + usersCollection.getName());
print("");

// 1. Total users with non-null phone
const totalPhoneUsers = usersCollection.countDocuments({ phone: { $ne: null, $exists: true } });
print("Total users with phone (non-null): " + totalPhoneUsers);

// 2. Canonical phone format: ^\+94[0-9]{9}$
const canonicalPhoneRegex = /^\+94[0-9]{9}$/;
const canonicalCount = usersCollection.countDocuments({ phone: { $regex: /^\+94[0-9]{9}$/ } });
print("Users with canonical phone (+94XXXXXXXXX): " + canonicalCount);

// 3. Non-canonical phone format
const nonCanonicalCount = totalPhoneUsers - canonicalCount;
print("Users with non-canonical phone format: " + nonCanonicalCount);
print("");

// 4. Detection of collision groups that normalize to the same canonical value
print("--- Checking for equivalent-form collisions across user records... ---");

function normalizeSriLankanPhone(raw) {
    if (!raw || typeof raw !== "string") return null;
    const cleaned = raw.trim().replace(/[\s\-\(\)]/g, "");
    if (/^(\+94|0|94)[0-9]{9}$/.test(cleaned)) {
        if (cleaned.startsWith("+94")) {
            return cleaned;
        } else if (cleaned.startsWith("0")) {
            return "+94" + cleaned.substring(1);
        } else if (cleaned.startsWith("94")) {
            return "+" + cleaned;
        }
    }
    return cleaned; // Fallback to raw cleaned for invalid/other patterns
}

const allPhoneDocs = usersCollection.find(
    { phone: { $ne: null, $exists: true } },
    { projection: { phone: 1 } }
).toArray();

const canonicalGroupCounts = {};
for (const doc of allPhoneDocs) {
    const canonical = normalizeSriLankanPhone(doc.phone);
    if (canonical) {
        canonicalGroupCounts[canonical] = (canonicalGroupCounts[canonical] || 0) + 1;
    }
}

let collisionGroupCount = 0;
const groupSizeDistribution = {};

for (const key in canonicalGroupCounts) {
    const size = canonicalGroupCounts[key];
    if (size > 1) {
        collisionGroupCount++;
        groupSizeDistribution[size] = (groupSizeDistribution[size] || 0) + 1;
    }
}

if (collisionGroupCount > 0) {
    print("[WARN] Detected " + collisionGroupCount + " group(s) of users with equivalent phone numbers.");
    print("Group size distribution (group size -> frequency):");
    for (const size in groupSizeDistribution) {
        print("  - Group size " + size + ": " + groupSizeDistribution[size] + " occurrence(s)");
    }
    print("");
    print("[NOTE] Application-level equivalent checking prevents new collisions on registration/profile update.");
    print("       Existing duplicate accounts must be resolved out-of-band if necessary.");
} else {
    print("[PASS] No collision groups found. All phone numbers map to unique canonical values.");
}

print("");
print("============================================================");
if (collisionGroupCount > 0) {
    print("PRE-CHECK RESULT: COMPLETED WITH WARNINGS (" + collisionGroupCount + " collision group(s))");
} else {
    print("PRE-CHECK RESULT: PASSED");
}
print("============================================================");

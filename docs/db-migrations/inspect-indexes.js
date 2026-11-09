// ===========================================================================
// Inspect Indexes (Read-Only)
// Purpose: Prints the index definitions for users and auth_tokens collections
// Usage: mongosh "$MONGODB_URI" docs/db-migrations/inspect-indexes.js
//
// NEVER print credentials or connection strings in this script or its output.
// ===========================================================================

const DB_NAME = "marketplace";
db = db.getSiblingDB(DB_NAME);

function printCollectionIndexes(collName) {
    print("------------------------------------------------------------");
    print("Collection: " + collName);
    print("------------------------------------------------------------");
    try {
        const indexes = db.getCollection(collName).getIndexes();
        if (!indexes || indexes.length === 0) {
            print("(No indexes found)");
            return;
        }
        indexes.forEach(idx => {
            const out = {
                name: idx.name,
                key: idx.key,
                unique: idx.unique !== undefined ? idx.unique : false
            };
            if (idx.partialFilterExpression) {
                out.partialFilterExpression = idx.partialFilterExpression;
            }
            if (idx.expireAfterSeconds !== undefined) {
                out.expireAfterSeconds = idx.expireAfterSeconds;
            }
            print(JSON.stringify(out, null, 2));
        });
    } catch (e) {
        print("[INFO] Collection '" + collName + "' has no indexes or does not exist: " + e.message);
    }
}

print("=== MONGODB INDEX DEFINITIONS (READ-ONLY) ===");
print("");
printCollectionIndexes("users");
print("");
printCollectionIndexes("auth_tokens");
print("");
print("[OK] Index inspection complete.");

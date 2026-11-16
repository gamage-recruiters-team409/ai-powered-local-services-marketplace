// ===========================================================================
// Backfill Address Slots (Idempotent)
// Purpose: Assigns atomic slot numbers (1..max) to existing customer_addresses
//          documents that do not currently have a slot field set.
// Usage: mongosh "$MONGODB_URI" docs/db-migrations/backfill-address-slots.js
//
// NEVER print credentials or connection strings in this script or its output.
// ===========================================================================

const DB_NAME = typeof process !== "undefined" && process.env && process.env.MONGODB_DATABASE
    ? process.env.MONGODB_DATABASE
    : "marketplace";
const targetDb = db.getSiblingDB(DB_NAME);

const rawLimit = typeof process !== "undefined" && process.env
    ? (process.env.CUSTOMER_MAX_ADDRESSES || process.env.MAX_ADDRESSES)
    : null;
const parsedLimit = rawLimit ? parseInt(rawLimit, 10) : 10;
const MAX_ADDRESSES = (!isNaN(parsedLimit) && parsedLimit > 0) ? parsedLimit : 10;

const collection = targetDb.getCollection("customer_addresses");

print("=== STARTING ADDRESS SLOT BACKFILL ===");
print("Max addresses configured: " + MAX_ADDRESSES);

const unslottedAddresses = collection.find({
    $or: [
        { slot: { $exists: false } },
        { slot: null }
    ]
}).sort({ customerId: 1, createdAt: 1 }).toArray();

print("Found " + unslottedAddresses.length + " addresses without a slot.");

if (unslottedAddresses.length === 0) {
    print("[OK] All addresses already have slots assigned. Nothing to backfill.");
} else {
    // Group unslotted addresses by customerId
    const customerMap = {};
    unslottedAddresses.forEach(addr => {
        const custId = addr.customerId;
        if (!customerMap[custId]) {
            customerMap[custId] = [];
        }
        customerMap[custId].push(addr);
    });

    let totalUpdated = 0;
    let totalSkippedOverLimit = 0;

    for (const customerId in customerMap) {
        // Query all existing addresses for this customer to find currently used slots
        const allCustomerAddresses = collection.find({ customerId: customerId }).toArray();
        const usedSlots = new Set();
        allCustomerAddresses.forEach(a => {
            if (a.slot && typeof a.slot === "number" && a.slot > 0) {
                usedSlots.add(a.slot);
            }
        });

        const unslottedForCustomer = customerMap[customerId];
        for (const addr of unslottedForCustomer) {
            // Find lowest available slot in 1..MAX_ADDRESSES
            let assignedSlot = null;
            for (let slot = 1; slot <= MAX_ADDRESSES; slot++) {
                if (!usedSlots.has(slot)) {
                    assignedSlot = slot;
                    break;
                }
            }

            if (assignedSlot !== null) {
                usedSlots.add(assignedSlot);
                collection.updateOne(
                    { _id: addr._id },
                    { $set: { slot: assignedSlot, updatedAt: new Date() } }
                );
                totalUpdated++;
            } else {
                print("[WARN] Customer '" + customerId + "' already has " + usedSlots.size +
                      " slots occupied. Skipping address " + addr._id + " (exceeds limit " + MAX_ADDRESSES + ").");
                totalSkippedOverLimit++;
            }
        }
    }

    print("Backfill complete. Updated: " + totalUpdated + ", Skipped (over limit): " + totalSkippedOverLimit);
}

print("=== VERIFYING NO DUPLICATE SLOTS EXIST ===");
const duplicateSlots = collection.aggregate([
    { $match: { slot: { $gt: 0 } } },
    { $group: { _id: { customerId: "$customerId", slot: "$slot" }, count: { $sum: 1 } } },
    { $match: { count: { $gt: 1 } } }
]).toArray();

if (duplicateSlots.length > 0) {
    print("[ERROR] Duplicate slots detected after backfill:");
    duplicateSlots.forEach(d => print(JSON.stringify(d)));
} else {
    print("[OK] Slot uniqueness verified across all customers.");
}

print("=== ADDRESS SLOT BACKFILL SCRIPT FINISHED ===");

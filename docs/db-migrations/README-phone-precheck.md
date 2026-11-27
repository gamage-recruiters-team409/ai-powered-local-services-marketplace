# User Phone Pre-Check Runbook

This runbook describes how to execute the read-only mongosh pre-check script `precheck-user-phones.js` against MongoDB (including MongoDB Atlas) to assess phone number formats and collision status prior to deploying changes.

---

## 1. Safety & Privacy Guarantees

- **100% Read-Only:** The script issues only `countDocuments` and read projections (`find({}, { phone: 1 })`). It modifies zero documents and creates/drops zero indexes.
- **Privacy Safe:** The script logs **only aggregate counts and group sizes**. It strictly avoids outputting user IDs, names, emails, or phone numbers to logs or console.
- **Credential Safe:** Connection strings containing credentials are never logged or echoed by the script.

---

## 2. Execution Instructions

### Against Local MongoDB / Docker
```bash
mongosh "mongodb://localhost:27017/marketplace" docs/db-migrations/precheck-user-phones.js
```

### Against MongoDB Atlas / Shared Cluster
Ensure your IP is allowlisted and the credentials are provided safely via environment variable:
```bash
export MONGODB_URI="mongodb+srv://<username>:<password>@cluster0.example.mongodb.net/marketplace?retryWrites=true&w=majority"
mongosh "$MONGODB_URI" docs/db-migrations/precheck-user-phones.js
```

---

## 3. Interpreting Output Metrics

1. **Total users with phone (non-null):**
   Total user documents in the `users` collection that have a non-null, existing `phone` field.

2. **Users with canonical phone (`+94XXXXXXXXX`):**
   Count of user records already stored in the canonical E.164-like format (`+94` followed by 9 digits).

3. **Users with non-canonical phone format:**
   Count of legacy users whose phone number was stored as entered (e.g., `0771234567`, `94771234567`, or with dashes/spaces).

4. **Collision Groups & Group Size Distribution:**
   - **`[PASS] No collision groups found`**: Every stored phone number maps to a unique canonical `+94XXXXXXXXX` number.
   - **`[WARN] Detected N group(s) of users with equivalent phone numbers`**: Indicates that two or more historical accounts hold different string representations of the same phone number (e.g. one user has `0771234567` and another has `+94771234567`).
   - Group size `2: K occurrence(s)` means there are `K` pairs of accounts that normalize to the same phone.
   - The application-level equivalent-form uniqueness check (`SriLankanPhoneUtil.equivalentForms` and `existsByPhoneIn` / `findByPhoneIn`) prevents *new* collisions on registration or profile updates. Existing duplicates can be evaluated for manual account resolution.

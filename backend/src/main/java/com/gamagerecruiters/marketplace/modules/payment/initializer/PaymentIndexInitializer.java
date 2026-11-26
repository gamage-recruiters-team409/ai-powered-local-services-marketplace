
package com.gamagerecruiters.marketplace.modules.payment.initializer;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

/**
 * Initializes and validates MongoDB indexes for payment transactions.
 *
 * <p>
 * Booking IDs are indexed without a uniqueness constraint because
 * a booking may have multiple payment attempts.
 * </p>
 *
 * <p>
 * Gateway references use a partial unique index to prevent duplicate
 * transaction references while allowing payments without a gateway
 * reference during initialization.
 * </p>
 *
 * <p>
 * Customer payment history uses a compound index on customer ID,
 * creation time, and MongoDB document ID. This supports the WP01
 * Customer Payments List with customer-scoped, newest-first pagination.
 * </p>
 *
 * <p>
 * Incompatible indexes are never dropped automatically. They require
 * an explicit database migration to protect existing financial data.
 * </p>
 */
@Component
@Profile("!test")
public class PaymentIndexInitializer {

    static final String PAYMENTS_COLLECTION = "payments";
    static final String BOOKING_ID_INDEX = "bookingId_1";
    static final String GATEWAY_REF_INDEX = "gatewayRef_1";
    static final String GATEWAY_PAYMENT_INTENT_INDEX = "gatewayPaymentIntentId_1";

    static final String CUSTOMER_HISTORY_INDEX = "customerId_1_createdAt_-1__id_-1";

    private static final Logger log = LoggerFactory.getLogger(PaymentIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public PaymentIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Verifies the required payment indexes when the application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing MongoDB indexes for payment transactions...");

        // Preserve previously approved payment foundation indexes.
        ensureIndex("bookingId", BOOKING_ID_INDEX, false, false);
        ensureIndex("gatewayRef", GATEWAY_REF_INDEX, true, true);

        // Prevent duplicate verified PaymentIntent references while
        // allowing legacy and uninitialized payment records.
        ensureIndex(
                "gatewayPaymentIntentId",
                GATEWAY_PAYMENT_INTENT_INDEX,
                true,
                true);

        // Support paginated customer payment history without booking-ID scans.
        ensureCustomerHistoryIndex();

        log.info("Payment transaction indexes verified successfully.");
    }

    /**
     * Creates a missing single-field index or validates an existing index.
     *
     * @param field     the indexed MongoDB document field
     * @param indexName the expected index name
     * @param unique    whether duplicate indexed values must be rejected
     * @param partial   whether only string values should be indexed
     */
    void ensureIndex(
            String field,
            String indexName,
            boolean unique,
            boolean partial) {
        try {
            ensureCollectionExists();

            MongoCollection<Document> collection = mongoTemplate.getCollection(PAYMENTS_COLLECTION);

            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

            Document existing = findByName(indexes, indexName);

            if (existing == null) {
                Document conflicting = findByField(indexes, field);

                if (conflicting != null) {
                    throw incompatible(indexName, conflicting);
                }

                IndexOptions options = new IndexOptions()
                        .name(indexName)
                        .unique(unique);

                if (partial) {
                    options.partialFilterExpression(
                            new Document(
                                    field,
                                    new Document("$type", "string")));
                }

                collection.createIndex(
                        Indexes.ascending(field),
                        options);

                indexes = collection.listIndexes().into(new ArrayList<>());
                existing = findByName(indexes, indexName);

                if (existing == null) {
                    throw new IllegalStateException(
                            "Payment index '" + indexName
                                    + "' was not found after creation.");
                }
            }

            if (!hasExpectedKey(existing, field)
                    || !hasExpectedUniqueness(existing, unique)
                    || !hasExpectedPartialFilter(existing, field, partial)) {
                throw incompatible(indexName, existing);
            }

            log.info(
                    "Payment index verified: {} -> {}",
                    indexName,
                    existing.toJson());

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to create or verify payment index '"
                            + indexName
                            + "'. Check MongoDB connectivity, existing indexes, "
                            + "and duplicate payment gateway references.",
                    e);
        }
    }

    /**
     * Creates or verifies the compound customer payment history index.
     *
     * <p>
     * The index supports filtering by customerId and sorting by
     * createdAt descending, followed by MongoDB _id descending.
     * It is non-unique because a customer may have many payments.
     * </p>
     *
     * <p>
     * Existing incompatible indexes are not automatically removed.
     * </p>
     */
    void ensureCustomerHistoryIndex() {
        try {
            ensureCollectionExists();

            MongoCollection<Document> collection = mongoTemplate.getCollection(PAYMENTS_COLLECTION);

            Document expectedKey = new Document("customerId", 1)
                    .append("createdAt", -1)
                    .append("_id", -1);

            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());

            Document existing = findByName(indexes, CUSTOMER_HISTORY_INDEX);

            if (existing == null) {
                Document conflicting = findByExactKey(indexes, expectedKey);

                if (conflicting != null) {
                    throw incompatible(
                            CUSTOMER_HISTORY_INDEX,
                            conflicting);
                }

                IndexOptions options = new IndexOptions()
                        .name(CUSTOMER_HISTORY_INDEX)
                        .unique(false);

                collection.createIndex(
                        Indexes.compoundIndex(
                                Indexes.ascending("customerId"),
                                Indexes.descending("createdAt"),
                                Indexes.descending("_id")),
                        options);

                indexes = collection.listIndexes().into(new ArrayList<>());
                existing = findByName(indexes, CUSTOMER_HISTORY_INDEX);

                if (existing == null) {
                    throw new IllegalStateException(
                            "Payment index '" + CUSTOMER_HISTORY_INDEX
                                    + "' was not found after creation.");
                }
            }

            if (!hasExpectedCompoundKey(existing, expectedKey)
                    || !hasExpectedUniqueness(existing, false)
                    || existing.get("partialFilterExpression") != null
                    || existing.get("sparse") != null
                    || existing.get("expireAfterSeconds") != null) {
                throw incompatible(CUSTOMER_HISTORY_INDEX, existing);
            }

            log.info(
                    "Payment customer history index verified: {} -> {}",
                    CUSTOMER_HISTORY_INDEX,
                    existing.toJson());

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to create or verify payment index '"
                            + CUSTOMER_HISTORY_INDEX
                            + "'. Check MongoDB connectivity and "
                            + "existing payment indexes.",
                    e);
        }
    }

    /**
     * Ensures the payments collection exists before creating indexes.
     */
    private void ensureCollectionExists() {
        if (mongoTemplate.collectionExists(PAYMENTS_COLLECTION)) {
            return;
        }

        try {
            mongoTemplate.createCollection(PAYMENTS_COLLECTION);
            log.info("Collection created: {}", PAYMENTS_COLLECTION);
        } catch (RuntimeException e) {
            if (!mongoTemplate.collectionExists(PAYMENTS_COLLECTION)) {
                throw e;
            }

            log.info("Payments collection was created concurrently.");
        }
    }

    private Document findByName(
            List<Document> indexes,
            String indexName) {
        for (Document index : indexes) {
            if (indexName.equals(index.getString("name"))) {
                return index;
            }
        }

        return null;
    }

    private Document findByField(
            List<Document> indexes,
            String field) {
        for (Document index : indexes) {
            if (hasExpectedKey(index, field)) {
                return index;
            }
        }

        return null;
    }

    /**
     * Finds an index with the exact expected compound key.
     *
     * <p>
     * MongoDB compound index field order matters, so both field
     * names and sort directions must match.
     * </p>
     */
    private Document findByExactKey(
            List<Document> indexes,
            Document expectedKey) {
        for (Document index : indexes) {
            if (hasExpectedCompoundKey(index, expectedKey)) {
                return index;
            }
        }

        return null;
    }

    private boolean hasExpectedKey(Document index, String field) {
        Document key = index.get("key", Document.class);

        if (key == null || key.size() != 1) {
            return false;
        }

        Object direction = key.get(field);

        return direction instanceof Number number
                && number.intValue() == 1;
    }

    /**
     * Verifies the complete compound key, including field order
     * and ascending or descending directions.
     */
    private boolean hasExpectedCompoundKey(
            Document index,
            Document expectedKey) {
        Document actualKey = index.get("key", Document.class);

        if (actualKey == null
                || actualKey.size() != expectedKey.size()) {
            return false;
        }

        var actualEntries = actualKey.entrySet().iterator();
        var expectedEntries = expectedKey.entrySet().iterator();

        while (actualEntries.hasNext() && expectedEntries.hasNext()) {
            var actual = actualEntries.next();
            var expected = expectedEntries.next();

            if (!actual.getKey().equals(expected.getKey())) {
                return false;
            }

            if (!(actual.getValue() instanceof Number actualDirection)
                    || !(expected.getValue() instanceof Number expectedDirection)
                    || actualDirection.intValue() != expectedDirection.intValue()) {
                return false;
            }
        }

        return !actualEntries.hasNext() && !expectedEntries.hasNext();
    }

    private boolean hasExpectedUniqueness(
            Document index,
            boolean expectedUnique) {
        Object value = index.get("unique");

        boolean actualUnique = false;

        if (value instanceof Boolean booleanValue) {
            actualUnique = booleanValue;
        } else if (value instanceof Number number) {
            actualUnique = number.intValue() == 1;
        }

        return actualUnique == expectedUnique;
    }

    private boolean hasExpectedPartialFilter(
            Document index,
            String field,
            boolean expectedPartial) {
        Document filter = index.get("partialFilterExpression", Document.class);

        if (!expectedPartial) {
            return filter == null;
        }

        if (filter == null || filter.size() != 1) {
            return false;
        }

        Document fieldFilter = filter.get(field, Document.class);

        return fieldFilter != null
                && fieldFilter.size() == 1
                && "string".equals(fieldFilter.getString("$type"));
    }

    private IllegalStateException incompatible(
            String indexName,
            Document found) {
        return new IllegalStateException(
                "Incompatible payment index '" + indexName
                        + "' detected: " + found.toJson()
                        + ". Automatic index deletion is disabled "
                        + "to protect financial records. "
                        + "Manual database migration is required.");
    }
}

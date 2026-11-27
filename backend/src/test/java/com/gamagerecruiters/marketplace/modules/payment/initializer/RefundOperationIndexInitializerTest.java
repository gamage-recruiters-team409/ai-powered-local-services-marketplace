
package com.gamagerecruiters.marketplace.modules.payment.initializer;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for refund operation MongoDB index initialization.
 *
 * <p>
 * Verifies index creation, uniqueness constraints, partial
 * filters, compound ordering and fail-closed validation.
 * </p>
 *
 * <p>
 * All MongoDB operations are mocked. These tests do not
 * connect to or modify a real database.
 * </p>
 */
class RefundOperationIndexInitializerTest {

    private static final String COLLECTION = RefundOperationIndexInitializer.REFUNDS_COLLECTION;

    private static final String IDEMPOTENCY_INDEX = RefundOperationIndexInitializer.IDEMPOTENCY_KEY_INDEX;

    private static final String GATEWAY_INDEX = RefundOperationIndexInitializer.GATEWAY_REFUND_REFERENCE_INDEX;

    private static final String RECONCILIATION_INDEX = RefundOperationIndexInitializer.RECONCILIATION_INDEX;

    private MongoTemplate mongoTemplate;

    private MongoCollection<Document> collection;

    private ListIndexesIterable<Document> indexIterable;

    private RefundOperationIndexInitializer initializer;

    private List<Document> storedIndexes;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {

        mongoTemplate = mock(MongoTemplate.class);
        collection = mock(MongoCollection.class);
        indexIterable = mock(ListIndexesIterable.class);

        initializer = new RefundOperationIndexInitializer(mongoTemplate);

        storedIndexes = new ArrayList<>();

        when(mongoTemplate.collectionExists(COLLECTION))
                .thenReturn(true);

        when(mongoTemplate.getCollection(COLLECTION))
                .thenReturn(collection);

        when(collection.listIndexes())
                .thenReturn(indexIterable);

        // Simulate MongoDB returning its persisted index metadata.
        when(indexIterable.into(
                org.mockito.ArgumentMatchers.<List<Document>>any()))
                .thenAnswer(invocation -> {

                    List<Document> target = invocation.getArgument(0);

                    target.addAll(storedIndexes);

                    return target;
                });

        // Simulate successful index creation and metadata persistence.
        when(collection.createIndex(
                any(Bson.class),
                any(IndexOptions.class)))
                .thenAnswer(invocation -> {

                    Document key = invocation.getArgument(0);
                    IndexOptions options = invocation.getArgument(1);

                    Document metadata = new Document(
                            "name", options.getName())
                            .append("key", new Document(key));

                    if (options.isUnique()) {
                        metadata.append("unique", true);
                    }

                    if (options.getPartialFilterExpression() != null) {
                        metadata.append(
                                "partialFilterExpression",
                                options.getPartialFilterExpression());
                    }

                    storedIndexes.add(metadata);

                    return options.getName();
                });
    }

    /**
     * Creates representative MongoDB index metadata.
     */
    private Document index(
            String name,
            Document key,
            boolean unique) {

        Document metadata = new Document("name", name)
                .append("key", key);

        if (unique) {
            metadata.append("unique", true);
        }

        return metadata;
    }

    private Document gatewayFilter() {

        return new Document(
                "gatewayRefundReference",
                new Document("$type", "string"));
    }

    private Document reconciliationKey() {

        return new Document("status", 1)
                .append("updatedAt", 1)
                .append("_id", 1);
    }

    private void ensureIdempotencyIndex() {

        initializer.ensureIndex(
                IDEMPOTENCY_INDEX,
                new Document("idempotencyKey", 1),
                true,
                null);
    }

    private void ensureGatewayIndex() {

        initializer.ensureIndex(
                GATEWAY_INDEX,
                new Document("gatewayRefundReference", 1),
                true,
                gatewayFilter());
    }

    private void ensureReconciliationIndex() {

        initializer.ensureIndex(
                RECONCILIATION_INDEX,
                reconciliationKey(),
                false,
                null);
    }

    @Test
    void shouldCreateAllFiveRequiredIndexes() {

        initializer.initializeIndexes();

        assertEquals(5, storedIndexes.size());

        verify(collection, times(5))
                .createIndex(
                        any(Bson.class),
                        any(IndexOptions.class));
    }

    @Test
    void shouldCreateMissingCollection() {

        when(mongoTemplate.collectionExists(COLLECTION))
                .thenReturn(false);

        initializer.initializeIndexes();

        verify(mongoTemplate)
                .createCollection(COLLECTION);

        assertEquals(5, storedIndexes.size());
    }

    @Test
    void shouldHandleConcurrentCollectionCreation() {

        when(mongoTemplate.collectionExists(COLLECTION))
                .thenReturn(false, true);

        when(mongoTemplate.createCollection(COLLECTION))
                .thenThrow(new RuntimeException(
                        "Collection already exists"));

        assertDoesNotThrow(
                initializer::initializeIndexes);

        assertEquals(5, storedIndexes.size());
    }

    @Test
    void shouldRejectCollectionCreationFailure() {

        when(mongoTemplate.collectionExists(COLLECTION))
                .thenReturn(false);

        when(mongoTemplate.createCollection(COLLECTION))
                .thenThrow(new RuntimeException(
                        "MongoDB unavailable"));

        assertThrows(
                RuntimeException.class,
                initializer::initializeIndexes);

        verify(collection, never())
                .createIndex(
                        any(Bson.class),
                        any(IndexOptions.class));
    }

    @Test
    void shouldCreateUniqueIdempotencyIndex() {

        ensureIdempotencyIndex();

        ArgumentCaptor<IndexOptions> captor = ArgumentCaptor.forClass(IndexOptions.class);

        verify(collection).createIndex(
                eq(new Document("idempotencyKey", 1)),
                captor.capture());

        IndexOptions options = captor.getValue();

        assertEquals(IDEMPOTENCY_INDEX, options.getName());
        assertTrue(options.isUnique());
        assertNull(options.getPartialFilterExpression());
    }

    @Test
    void shouldCreatePartialUniqueGatewayIndex() {

        ensureGatewayIndex();

        ArgumentCaptor<IndexOptions> captor = ArgumentCaptor.forClass(IndexOptions.class);

        verify(collection).createIndex(
                eq(new Document("gatewayRefundReference", 1)),
                captor.capture());

        IndexOptions options = captor.getValue();

        assertEquals(GATEWAY_INDEX, options.getName());
        assertTrue(options.isUnique());

        assertEquals(
                gatewayFilter(),
                options.getPartialFilterExpression());
    }

    @Test
    void shouldCreateNonUniquePaymentIndex() {

        initializer.initializeIndexes();

        Document metadata = storedIndexes.stream()
                .filter(value -> RefundOperationIndexInitializer.PAYMENT_ID_INDEX
                        .equals(value.getString("name")))
                .findFirst()
                .orElseThrow();

        assertEquals(
                new Document("paymentId", 1),
                metadata.get("key"));

        assertFalse(
                metadata.getBoolean("unique", false));
    }

    @Test
    void shouldCreateNonUniqueDisputeIndex() {

        initializer.initializeIndexes();

        Document metadata = storedIndexes.stream()
                .filter(value -> RefundOperationIndexInitializer.DISPUTE_ID_INDEX
                        .equals(value.getString("name")))
                .findFirst()
                .orElseThrow();

        assertEquals(
                new Document("disputeId", 1),
                metadata.get("key"));

        assertFalse(
                metadata.getBoolean("unique", false));
    }

    @Test
    void shouldCreateOrderedReconciliationIndex() {

        ensureReconciliationIndex();

        ArgumentCaptor<Bson> keyCaptor = ArgumentCaptor.forClass(Bson.class);

        ArgumentCaptor<IndexOptions> optionsCaptor = ArgumentCaptor.forClass(IndexOptions.class);

        verify(collection).createIndex(
                keyCaptor.capture(),
                optionsCaptor.capture());

        Document actualKey = (Document) keyCaptor.getValue();

        assertEquals(
                List.of("status", "updatedAt", "_id"),
                new ArrayList<>(actualKey.keySet()));

        assertEquals(reconciliationKey(), actualKey);

        assertFalse(
                optionsCaptor.getValue().isUnique());
    }

    @Test
    void shouldAcceptCompatibleExistingIndexes() {

        initializer.initializeIndexes();

        clearInvocations(collection);

        assertDoesNotThrow(
                initializer::initializeIndexes);

        verify(collection, never())
                .createIndex(
                        any(Bson.class),
                        any(IndexOptions.class));

        assertEquals(5, storedIndexes.size());
    }

    @Test
    void shouldRejectNonUniqueIdempotencyIndex() {

        storedIndexes.add(index(
                IDEMPOTENCY_INDEX,
                new Document("idempotencyKey", 1),
                false));

        assertThrows(
                IllegalStateException.class,
                this::ensureIdempotencyIndex);
    }

    @Test
    void shouldRejectNonUniqueGatewayIndex() {

        storedIndexes.add(index(
                GATEWAY_INDEX,
                new Document("gatewayRefundReference", 1),
                false)
                .append(
                        "partialFilterExpression",
                        gatewayFilter()));

        assertThrows(
                IllegalStateException.class,
                this::ensureGatewayIndex);
    }

    @Test
    void shouldRejectMissingGatewayPartialFilter() {

        storedIndexes.add(index(
                GATEWAY_INDEX,
                new Document("gatewayRefundReference", 1),
                true));

        assertThrows(
                IllegalStateException.class,
                this::ensureGatewayIndex);
    }

    @Test
    void shouldRejectIncorrectGatewayPartialFilter() {

        storedIndexes.add(index(
                GATEWAY_INDEX,
                new Document("gatewayRefundReference", 1),
                true)
                .append(
                        "partialFilterExpression",
                        new Document(
                                "gatewayRefundReference",
                                new Document("$exists", true))));

        assertThrows(
                IllegalStateException.class,
                this::ensureGatewayIndex);
    }

    @Test
    void shouldRejectUnexpectedIdempotencyPartialFilter() {

        storedIndexes.add(index(
                IDEMPOTENCY_INDEX,
                new Document("idempotencyKey", 1),
                true)
                .append(
                        "partialFilterExpression",
                        new Document(
                                "idempotencyKey",
                                new Document("$type", "string"))));

        assertThrows(
                IllegalStateException.class,
                this::ensureIdempotencyIndex);
    }

    @Test
    void shouldRejectIncorrectCompoundFieldOrder() {

        storedIndexes.add(index(
                RECONCILIATION_INDEX,
                new Document("updatedAt", 1)
                        .append("status", 1)
                        .append("_id", 1),
                false));

        assertThrows(
                IllegalStateException.class,
                this::ensureReconciliationIndex);
    }

    @Test
    void shouldRejectIncorrectCompoundSortDirection() {

        storedIndexes.add(index(
                RECONCILIATION_INDEX,
                new Document("status", 1)
                        .append("updatedAt", -1)
                        .append("_id", 1),
                false));

        assertThrows(
                IllegalStateException.class,
                this::ensureReconciliationIndex);
    }

    @Test
    void shouldRejectConflictingIndexName() {

        storedIndexes.add(index(
                "legacy_refund_idempotency_index",
                new Document("idempotencyKey", 1),
                true));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                this::ensureIdempotencyIndex);

        assertTrue(exception.getMessage()
                .contains("Manual database migration"));

        verify(collection, never())
                .createIndex(
                        any(Bson.class),
                        any(IndexOptions.class));

        verify(collection, never())
                .dropIndex(anyString());

        verify(collection, never())
                .dropIndex(any(Bson.class));

        verify(collection, never())
                .dropIndexes();
    }

    @Test
    void shouldRejectIncorrectKeyUnderExpectedName() {

        storedIndexes.add(index(
                IDEMPOTENCY_INDEX,
                new Document("paymentId", 1),
                true));

        assertThrows(
                IllegalStateException.class,
                this::ensureIdempotencyIndex);
    }

    @Test
    void shouldRejectSparseIndex() {

        storedIndexes.add(index(
                IDEMPOTENCY_INDEX,
                new Document("idempotencyKey", 1),
                true)
                .append("sparse", true));

        assertThrows(
                IllegalStateException.class,
                this::ensureIdempotencyIndex);
    }

    @Test
    void shouldRejectUnexpectedTtlIndex() {

        storedIndexes.add(index(
                IDEMPOTENCY_INDEX,
                new Document("idempotencyKey", 1),
                true)
                .append("expireAfterSeconds", 3600));

        assertThrows(
                IllegalStateException.class,
                this::ensureIdempotencyIndex);
    }

    @Test
    void shouldRejectHiddenIndex() {

        storedIndexes.add(index(
                IDEMPOTENCY_INDEX,
                new Document("idempotencyKey", 1),
                true)
                .append("hidden", true));

        assertThrows(
                IllegalStateException.class,
                this::ensureIdempotencyIndex);
    }

    @Test
    void shouldRejectNonSimpleCollation() {

        storedIndexes.add(index(
                IDEMPOTENCY_INDEX,
                new Document("idempotencyKey", 1),
                true)
                .append(
                        "collation",
                        new Document("locale", "en")
                                .append("strength", 2)));

        assertThrows(
                IllegalStateException.class,
                this::ensureIdempotencyIndex);
    }

    @Test
    void shouldAcceptSimpleCollation() {

        storedIndexes.add(index(
                IDEMPOTENCY_INDEX,
                new Document("idempotencyKey", 1),
                true)
                .append(
                        "collation",
                        new Document("locale", "simple")));

        assertDoesNotThrow(
                this::ensureIdempotencyIndex);
    }

    @Test
    void shouldFailWhenIndexCreationFails() {

        when(collection.createIndex(
                any(Bson.class),
                any(IndexOptions.class)))
                .thenThrow(new RuntimeException(
                        "Duplicate financial references"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                this::ensureIdempotencyIndex);

        assertTrue(exception.getMessage()
                .contains("Unable to create or verify"));

        assertNotNull(exception.getCause());
    }

    @Test
    void shouldFailWhenCreatedIndexCannotBeVerified() {

        when(collection.createIndex(
                any(Bson.class),
                any(IndexOptions.class)))
                .thenReturn(IDEMPOTENCY_INDEX);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                this::ensureIdempotencyIndex);

        assertTrue(exception.getMessage()
                .contains("was not found after creation"));
    }

    @Test
    void shouldFailWhenIndexMetadataCannotBeRead() {

        when(collection.listIndexes())
                .thenThrow(new RuntimeException(
                        "MongoDB connection unavailable"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                this::ensureIdempotencyIndex);

        assertNotNull(exception.getCause());
    }
}

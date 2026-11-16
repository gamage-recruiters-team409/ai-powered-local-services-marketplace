
package com.gamagerecruiters.marketplace.modules.payment.initializer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
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

/**
 * Unit tests for MongoDB payment index initialization.
 *
 * <p>
 * Uses Mockito to verify index creation, validation, and
 * protection against incompatible index configurations
 * without connecting to a live MongoDB database.
 * </p>
 */
class PaymentIndexInitializerTest {

    private MongoTemplate mongoTemplate;
    private MongoCollection<Document> collection;
    private PaymentIndexInitializer initializer;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        collection = mock(MongoCollection.class);

        when(mongoTemplate.collectionExists("payments"))
                .thenReturn(true);

        when(mongoTemplate.getCollection("payments"))
                .thenReturn(collection);

        initializer = new PaymentIndexInitializer(mongoTemplate);
    }

    /**
     * Verifies that a valid non-unique booking index
     * is accepted without creating a duplicate index.
     */
    @Test
    void shouldAcceptExistingBookingIndex() {
        Document index = bookingIndex();

        mockIndexes(index);

        initializer.ensureIndex(
                "bookingId", "bookingId_1", false, false);

        verify(collection, never())
                .createIndex(any(Bson.class), any(IndexOptions.class));
    }

    /**
     * Verifies that an existing partial unique gateway index
     * matches the required payment index configuration.
     */
    @Test
    void shouldAcceptExistingGatewayIndex() {
        Document index = gatewayIndex();

        mockIndexes(index);

        initializer.ensureIndex(
                "gatewayRef", "gatewayRef_1", true, true);

        verify(collection, never())
                .createIndex(any(Bson.class), any(IndexOptions.class));
    }

    /**
     * Ensures that a gateway index without uniqueness
     * is rejected to protect transaction references.
     */
    @Test
    void shouldRejectNonUniqueGatewayIndex() {
        Document index = new Document("name", "gatewayRef_1")
                .append("key", new Document("gatewayRef", 1));

        mockIndexes(index);

        assertThrows(
                IllegalStateException.class,
                () -> initializer.ensureIndex(
                        "gatewayRef", "gatewayRef_1", true, true));
    }

    /**
     * Ensures that a gateway index missing its partial
     * filter expression is rejected.
     */
    @Test
    void shouldRejectGatewayIndexWithoutPartialFilter() {
        Document index = new Document("name", "gatewayRef_1")
                .append("key", new Document("gatewayRef", 1))
                .append("unique", true);

        mockIndexes(index);

        assertThrows(
                IllegalStateException.class,
                () -> initializer.ensureIndex(
                        "gatewayRef", "gatewayRef_1", true, true));
    }

    /**
     * Ensures that incompatible financial indexes
     * are never automatically deleted.
     */
    @Test
    void shouldNotDropIncompatibleIndexes() {
        Document index = new Document("name", "gatewayRef_1")
                .append("key", new Document("gatewayRef", 1));

        mockIndexes(index);

        assertThrows(
                IllegalStateException.class,
                () -> initializer.ensureIndex(
                        "gatewayRef", "gatewayRef_1", true, true));

        verify(collection, never()).dropIndex(anyString());
    }

    /**
     * Verifies that a missing booking index is created
     * and validated after creation.
     *
     * <p>
     * The mock simulates MongoDB returning no indexes
     * initially and the new index on the next lookup.
     * </p>
     */
    @Test
    void shouldCreateMissingBookingIndex() {
        mockIndexCreation(bookingIndex());

        initializer.ensureIndex(
                "bookingId", "bookingId_1", false, false);

        ArgumentCaptor<IndexOptions> optionsCaptor = ArgumentCaptor.forClass(IndexOptions.class);

        verify(collection, times(1))
                .createIndex(any(Bson.class), optionsCaptor.capture());

        IndexOptions options = optionsCaptor.getValue();

        assertTrue(!options.isUnique());
        assertTrue("bookingId_1".equals(options.getName()));
    }

    /**
     * Verifies that a missing gateway index is created
     * with uniqueness and a string-only partial filter.
     */
    @Test
    void shouldCreateMissingGatewayIndex() {
        mockIndexCreation(gatewayIndex());

        initializer.ensureIndex(
                "gatewayRef", "gatewayRef_1", true, true);

        ArgumentCaptor<IndexOptions> optionsCaptor = ArgumentCaptor.forClass(IndexOptions.class);

        verify(collection, times(1))
                .createIndex(any(Bson.class), optionsCaptor.capture());

        IndexOptions options = optionsCaptor.getValue();

        assertTrue(Boolean.TRUE.equals(options.isUnique()));
        assertTrue("gatewayRef_1".equals(options.getName()));
        assertTrue(
                new Document(
                        "gatewayRef",
                        new Document("$type", "string")).equals(options.getPartialFilterExpression()));
    }

    /**
     * Creates metadata for a valid non-unique booking index.
     *
     * @return booking index metadata
     */
    private Document bookingIndex() {
        return new Document("name", "bookingId_1")
                .append("key", new Document("bookingId", 1));
    }

    /**
     * Creates metadata for a valid partial unique gateway index.
     *
     * @return gateway index metadata
     */
    private Document gatewayIndex() {
        return new Document("name", "gatewayRef_1")
                .append("key", new Document("gatewayRef", 1))
                .append("unique", true)
                .append(
                        "partialFilterExpression",
                        new Document(
                                "gatewayRef",
                                new Document("$type", "string")));
    }

    /**
     * Mocks an existing MongoDB index listing.
     *
     * @param indexes indexes returned by the mocked database
     */
    @SuppressWarnings("unchecked")
    private void mockIndexes(Document... indexes) {
        ListIndexesIterable<Document> iterable = mock(ListIndexesIterable.class);

        when(collection.listIndexes()).thenReturn(iterable);

        when(iterable.into(anyCollection()))
                .thenAnswer(invocation -> {
                    Collection<Document> destination = invocation.getArgument(0);

                    destination.addAll(List.of(indexes));
                    return destination;
                });
    }

    /**
     * Simulates MongoDB index creation by returning an empty
     * index list first and the newly created index afterward.
     *
     * @param createdIndex metadata of the newly created index
     */
    @SuppressWarnings("unchecked")
    private void mockIndexCreation(Document createdIndex) {
        ListIndexesIterable<Document> beforeCreation = mock(ListIndexesIterable.class);

        ListIndexesIterable<Document> afterCreation = mock(ListIndexesIterable.class);

        when(collection.listIndexes())
                .thenReturn(beforeCreation, afterCreation);

        when(beforeCreation.into(anyCollection()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(afterCreation.into(anyCollection()))
                .thenAnswer(invocation -> {
                    Collection<Document> destination = invocation.getArgument(0);

                    destination.add(createdIndex);
                    return destination;
                });
    }
}

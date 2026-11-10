package com.gamagerecruiters.marketplace.modules.booking.initializer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;

@ExtendWith(MockitoExtension.class)
class BookingIndexInitializerTest {

    private static final Document ID_INDEX = new Document("v", 2)
            .append("key", new Document("_id", 1)).append("name", "_id_");

    private static final Document REF_UNIQUE = new Document("v", 2)
            .append("key", new Document("ref", 1)).append("name", "ref_1").append("unique", true);

    private static final Document PROVIDER_SLOT = new Document("v", 2)
            .append("key", new Document("providerId", 1).append("slotStart", 1))
            .append("name", "providerId_1_slotStart_1");

    private static final Document CUSTOMER_STATUS = new Document("v", 2)
            .append("key", new Document("customerId", 1).append("status", 1))
            .append("name", "customerId_1_status_1");

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private MongoCollection<Document> bookingsCollection;

    private BookingIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new BookingIndexInitializer(mongoTemplate);
    }

    @SuppressWarnings("unchecked")
    private static ListIndexesIterable<Document> indexes(Document... docs) {
        ListIndexesIterable<Document> iterable = mock(ListIndexesIterable.class);
        when(iterable.into(any())).thenAnswer(invocation -> {
            Collection<Document> target = invocation.getArgument(0);
            target.addAll(List.of(docs));
            return target;
        });
        return iterable;
    }

    @Test
    @DisplayName("Fresh database: creates the bookings collection first, then creates and verifies all 3 indexes")
    void freshDatabaseCreatesCollectionThenIndexes() {
        when(mongoTemplate.collectionExists("bookings")).thenReturn(false);
        when(mongoTemplate.getCollection("bookings")).thenReturn(bookingsCollection);

        ListIndexesIterable<Document> empty1 = indexes(ID_INDEX);
        ListIndexesIterable<Document> afterRef = indexes(ID_INDEX, REF_UNIQUE);
        ListIndexesIterable<Document> beforeSlot = indexes(ID_INDEX, REF_UNIQUE);
        ListIndexesIterable<Document> afterSlot = indexes(ID_INDEX, REF_UNIQUE, PROVIDER_SLOT);
        ListIndexesIterable<Document> beforeCustomer = indexes(ID_INDEX, REF_UNIQUE, PROVIDER_SLOT);
        ListIndexesIterable<Document> afterCustomer = indexes(ID_INDEX, REF_UNIQUE, PROVIDER_SLOT, CUSTOMER_STATUS);
        when(bookingsCollection.listIndexes())
                .thenReturn(empty1, afterRef, beforeSlot, afterSlot, beforeCustomer, afterCustomer);

        assertThatCode(initializer::initializeIndexes).doesNotThrowAnyException();

        InOrder order = inOrder(mongoTemplate, bookingsCollection);
        order.verify(mongoTemplate).createCollection("bookings");
        order.verify(bookingsCollection, atLeastOnce()).listIndexes();
        verify(bookingsCollection, times(3)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("Existing compatible indexes are accepted without creating anything")
    void acceptsExistingIndexes() {
        when(mongoTemplate.collectionExists("bookings")).thenReturn(true);
        when(mongoTemplate.getCollection("bookings")).thenReturn(bookingsCollection);
        ListIndexesIterable<Document> all = indexes(ID_INDEX, REF_UNIQUE, PROVIDER_SLOT, CUSTOMER_STATUS);
        when(bookingsCollection.listIndexes()).thenReturn(all);

        assertThatCode(initializer::initializeIndexes).doesNotThrowAnyException();

        verify(mongoTemplate, never()).createCollection(anyString());
        verify(bookingsCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("Fails when ref index exists but is not unique (never drops it)")
    void failsWhenRefIndexNotUnique() {
        Document refNotUnique = new Document("v", 2)
                .append("key", new Document("ref", 1)).append("name", "ref_1");
        when(mongoTemplate.getCollection("bookings")).thenReturn(bookingsCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, refNotUnique);
        when(bookingsCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureIndex("bookings", List.of("ref"), "ref_1", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index")
                .hasMessageContaining("Manual migration required");

        verify(bookingsCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
        verify(bookingsCollection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails when (providerId, slotStart) exists as unique (would block valid bookings)")
    void failsWhenProviderSlotIndexIsUnique() {
        Document uniqueSlot = new Document("v", 2)
                .append("key", new Document("providerId", 1).append("slotStart", 1))
                .append("name", "providerId_1_slotStart_1").append("unique", true);
        when(mongoTemplate.getCollection("bookings")).thenReturn(bookingsCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, uniqueSlot);
        when(bookingsCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureIndex("bookings", List.of("providerId", "slotStart"),
                "providerId_1_slotStart_1", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index");
        verify(bookingsCollection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails when the index name is used by a different field order")
    void failsWhenNameUsedByDifferentKey() {
        Document wrongOrder = new Document("v", 2)
                .append("key", new Document("status", 1).append("customerId", 1))
                .append("name", "customerId_1_status_1");
        when(mongoTemplate.getCollection("bookings")).thenReturn(bookingsCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, wrongOrder);
        when(bookingsCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureIndex("bookings", List.of("customerId", "status"),
                "customerId_1_status_1", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index");
        verify(bookingsCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("Fails when the unique ref index is partial")
    void failsWhenRefIndexIsPartial() {
        Document partial = new Document("v", 2)
                .append("key", new Document("ref", 1)).append("name", "ref_1").append("unique", true)
                .append("partialFilterExpression", new Document("ref", new Document("$type", "string")));
        when(mongoTemplate.getCollection("bookings")).thenReturn(bookingsCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, partial);
        when(bookingsCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureIndex("bookings", List.of("ref"), "ref_1", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index");
    }

    @Test
    @DisplayName("Fails when the index is still missing after creation")
    void failsWhenMissingAfterCreation() {
        when(mongoTemplate.getCollection("bookings")).thenReturn(bookingsCollection);
        ListIndexesIterable<Document> before = indexes(ID_INDEX);
        ListIndexesIterable<Document> after = indexes(ID_INDEX);
        when(bookingsCollection.listIndexes()).thenReturn(before, after);

        assertThatThrownBy(() -> initializer.ensureIndex("bookings", List.of("ref"), "ref_1", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was not found after creation");
    }

    @Test
    @DisplayName("Accepts a collection created at the same moment by another instance")
    void acceptsCollectionCreatedConcurrently() {
        when(mongoTemplate.collectionExists("bookings")).thenReturn(false, true);
        when(mongoTemplate.createCollection("bookings"))
                .thenThrow(new RuntimeException("NamespaceExists: collection already exists"));

        assertThatCode(() -> initializer.ensureCollectionExists("bookings")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Fails safely when the collection cannot be created")
    void failsWhenCollectionCannotBeCreated() {
        when(mongoTemplate.collectionExists("bookings")).thenReturn(false, false);
        when(mongoTemplate.createCollection("bookings")).thenThrow(new RuntimeException("not authorized"));

        assertThatThrownBy(() -> initializer.ensureCollectionExists("bookings"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Startup failed");
    }
}

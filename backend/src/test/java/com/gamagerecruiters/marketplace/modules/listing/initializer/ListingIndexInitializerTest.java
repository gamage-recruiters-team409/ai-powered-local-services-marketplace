package com.gamagerecruiters.marketplace.modules.listing.initializer;

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
class ListingIndexInitializerTest {

    private static final Document ID_INDEX = new Document("v", 2)
            .append("key", new Document("_id", 1)).append("name", "_id_");

    private static final Document PROVIDER_STATUS = new Document("v", 2)
            .append("key", new Document("providerId", 1).append("status", 1))
            .append("name", "providerId_1_status_1");

    private static final Document CATEGORY_STATUS = new Document("v", 2)
            .append("key", new Document("categoryId", 1).append("status", 1))
            .append("name", "categoryId_1_status_1");

    private static final Document STATUS_CREATED = new Document("v", 2)
            .append("key", new Document("status", 1).append("createdAt", 1))
            .append("name", "status_1_createdAt_1");

    private static final Document LOCATION_2DSPHERE = new Document("v", 2)
            .append("key", new Document("location", "2dsphere"))
            .append("name", "location_2dsphere")
            .append("2dsphereIndexVersion", 3);

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private MongoCollection<Document> listingsCollection;

    private ListingIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new ListingIndexInitializer(mongoTemplate);
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
    @DisplayName("Fresh database: creates service_listings collection first, then creates and verifies all 4 indexes")
    void freshDatabaseCreatesCollectionThenIndexes() {
        when(mongoTemplate.collectionExists("service_listings")).thenReturn(false);
        when(mongoTemplate.getCollection("service_listings")).thenReturn(listingsCollection);

        ListIndexesIterable<Document> initial = indexes(ID_INDEX);
        ListIndexesIterable<Document> afterProvider = indexes(ID_INDEX, PROVIDER_STATUS);
        ListIndexesIterable<Document> beforeCategory = indexes(ID_INDEX, PROVIDER_STATUS);
        ListIndexesIterable<Document> afterCategory = indexes(ID_INDEX, PROVIDER_STATUS, CATEGORY_STATUS);
        ListIndexesIterable<Document> beforeStatus = indexes(ID_INDEX, PROVIDER_STATUS, CATEGORY_STATUS);
        ListIndexesIterable<Document> afterStatus =
                indexes(ID_INDEX, PROVIDER_STATUS, CATEGORY_STATUS, STATUS_CREATED);
        ListIndexesIterable<Document> beforeGeo =
                indexes(ID_INDEX, PROVIDER_STATUS, CATEGORY_STATUS, STATUS_CREATED);
        ListIndexesIterable<Document> afterGeo =
                indexes(ID_INDEX, PROVIDER_STATUS, CATEGORY_STATUS, STATUS_CREATED, LOCATION_2DSPHERE);

        when(listingsCollection.listIndexes()).thenReturn(
                initial, afterProvider, beforeCategory, afterCategory, beforeStatus, afterStatus, beforeGeo, afterGeo);

        assertThatCode(initializer::initializeIndexes).doesNotThrowAnyException();

        InOrder order = inOrder(mongoTemplate, listingsCollection);
        order.verify(mongoTemplate).createCollection("service_listings");
        order.verify(listingsCollection, atLeastOnce()).listIndexes();
        verify(listingsCollection, times(4)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("Existing compatible indexes are accepted without creating or dropping anything")
    void acceptsExistingIndexes() {
        when(mongoTemplate.collectionExists("service_listings")).thenReturn(true);
        when(mongoTemplate.getCollection("service_listings")).thenReturn(listingsCollection);
        ListIndexesIterable<Document> all =
                indexes(ID_INDEX, PROVIDER_STATUS, CATEGORY_STATUS, STATUS_CREATED, LOCATION_2DSPHERE);
        when(listingsCollection.listIndexes()).thenReturn(all);

        assertThatCode(initializer::initializeIndexes).doesNotThrowAnyException();

        verify(mongoTemplate, never()).createCollection(anyString());
        verify(listingsCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
        verify(listingsCollection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails when (providerId, status) exists as unique (would block multiple listings per provider)")
    void failsWhenProviderStatusIndexIsUnique() {
        Document uniqueProviderStatus = new Document("v", 2)
                .append("key", new Document("providerId", 1).append("status", 1))
                .append("name", "providerId_1_status_1")
                .append("unique", true);
        when(mongoTemplate.getCollection("service_listings")).thenReturn(listingsCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, uniqueProviderStatus);
        when(listingsCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureAscendingIndex(
                "service_listings", List.of("providerId", "status"), "providerId_1_status_1", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index")
                .hasMessageContaining("Manual migration required");

        verify(listingsCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
        verify(listingsCollection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails when target index name is held by wrong field order")
    void failsWhenNameUsedByDifferentFieldOrder() {
        Document reversedOrder = new Document("v", 2)
                .append("key", new Document("status", 1).append("categoryId", 1))
                .append("name", "categoryId_1_status_1");
        when(mongoTemplate.getCollection("service_listings")).thenReturn(listingsCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, reversedOrder);
        when(listingsCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureAscendingIndex(
                "service_listings", List.of("categoryId", "status"), "categoryId_1_status_1", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index");

        verify(listingsCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("Fails when ascending index has partialFilterExpression")
    void failsWhenAscendingIndexIsPartial() {
        Document partial = new Document("v", 2)
                .append("key", new Document("status", 1).append("createdAt", 1))
                .append("name", "status_1_createdAt_1")
                .append("partialFilterExpression", new Document("status", "FLAGGED"));
        when(mongoTemplate.getCollection("service_listings")).thenReturn(listingsCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, partial);
        when(listingsCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureAscendingIndex(
                "service_listings", List.of("status", "createdAt"), "status_1_createdAt_1", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index");
    }

    @Test
    @DisplayName("Fails when ascending index has numeric sparse: 1")
    void failsWhenAscendingIndexSparseIsNumeric() {
        Document numericSparse = new Document("v", 2)
                .append("key", new Document("status", 1).append("createdAt", 1))
                .append("name", "status_1_createdAt_1")
                .append("sparse", 1);
        when(mongoTemplate.getCollection("service_listings")).thenReturn(listingsCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, numericSparse);
        when(listingsCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureAscendingIndex(
                "service_listings", List.of("status", "createdAt"), "status_1_createdAt_1", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index");
    }

    @Test
    @DisplayName("Fails when location_2dsphere index name holds ascending key instead of 2dsphere")
    void failsWhenLocationIndexIsNot2dsphere() {
        Document wrongLocationType = new Document("v", 2)
                .append("key", new Document("location", 1))
                .append("name", "location_2dsphere");
        when(mongoTemplate.getCollection("service_listings")).thenReturn(listingsCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, wrongLocationType);
        when(listingsCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureGeo2dsphereIndex(
                "service_listings", "location", "location_2dsphere"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index")
                .hasMessageContaining("2dsphere");

        verify(listingsCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
        verify(listingsCollection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails when 2dsphere index is sparse")
    void failsWhen2dsphereIndexIsSparse() {
        Document sparseGeo = new Document("v", 2)
                .append("key", new Document("location", "2dsphere"))
                .append("name", "location_2dsphere")
                .append("sparse", true);
        when(mongoTemplate.getCollection("service_listings")).thenReturn(listingsCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, sparseGeo);
        when(listingsCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureGeo2dsphereIndex(
                "service_listings", "location", "location_2dsphere"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index");
    }

    @Test
    @DisplayName("Fails when created index is missing when listIndexes is re-read")
    void failsWhenMissingAfterCreation() {
        when(mongoTemplate.getCollection("service_listings")).thenReturn(listingsCollection);
        ListIndexesIterable<Document> before = indexes(ID_INDEX);
        ListIndexesIterable<Document> after = indexes(ID_INDEX);
        when(listingsCollection.listIndexes()).thenReturn(before, after);

        assertThatThrownBy(() -> initializer.ensureGeo2dsphereIndex(
                "service_listings", "location", "location_2dsphere"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was not found after creation");
    }

    @Test
    @DisplayName("Accepts collection created concurrently by another instance")
    void acceptsCollectionCreatedConcurrently() {
        when(mongoTemplate.collectionExists("service_listings")).thenReturn(false, true);
        when(mongoTemplate.createCollection("service_listings"))
                .thenThrow(new RuntimeException("NamespaceExists"));

        assertThatCode(() -> initializer.ensureCollectionExists("service_listings")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Fails safely when the collection cannot be created")
    void failsWhenCollectionCannotBeCreated() {
        when(mongoTemplate.collectionExists("service_listings")).thenReturn(false, false);
        when(mongoTemplate.createCollection("service_listings"))
                .thenThrow(new RuntimeException("not authorized"));

        assertThatThrownBy(() -> initializer.ensureCollectionExists("service_listings"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Startup failed");
    }
}

package com.gamagerecruiters.marketplace.modules.availability.initializer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
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
class AvailabilityIndexInitializerTest {

    private static final Document ID_INDEX = new Document("v", 2)
            .append("key", new Document("_id", 1))
            .append("name", "_id_");

    private static final Document PROVIDER_UNIQUE = new Document("v", 2)
            .append("key", new Document("providerId", 1))
            .append("name", "providerId_1")
            .append("unique", true);

    private static final Document PROVIDER_DATE_UNIQUE = new Document("v", 2)
            .append("key", new Document("providerId", 1).append("date", 1))
            .append("name", "providerId_1_date_1")
            .append("unique", true);

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private MongoCollection<Document> rulesCollection;

    @Mock
    private MongoCollection<Document> blockedDatesCollection;

    private AvailabilityIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new AvailabilityIndexInitializer(mongoTemplate);
    }

    /** Returns an iterable whose into(...) copies the given index documents into the target list. */
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
    @DisplayName("Fresh database: creates missing collections first, then creates and verifies both unique indexes")
    void freshDatabaseCreatesCollectionsThenIndexes() {
        when(mongoTemplate.collectionExists("availability_rules")).thenReturn(false);
        when(mongoTemplate.collectionExists("blocked_dates")).thenReturn(false);
        when(mongoTemplate.getCollection("availability_rules")).thenReturn(rulesCollection);
        when(mongoTemplate.getCollection("blocked_dates")).thenReturn(blockedDatesCollection);

        ListIndexesIterable<Document> rulesBefore = indexes(ID_INDEX);
        ListIndexesIterable<Document> rulesAfter = indexes(ID_INDEX, PROVIDER_UNIQUE);
        ListIndexesIterable<Document> blockedBefore = indexes(ID_INDEX);
        ListIndexesIterable<Document> blockedAfter = indexes(ID_INDEX, PROVIDER_DATE_UNIQUE);
        when(rulesCollection.listIndexes()).thenReturn(rulesBefore, rulesAfter);
        when(blockedDatesCollection.listIndexes()).thenReturn(blockedBefore, blockedAfter);

        assertThatCode(initializer::initializeIndexes).doesNotThrowAnyException();

        InOrder order = inOrder(mongoTemplate, rulesCollection);
        order.verify(mongoTemplate).createCollection("availability_rules");
        order.verify(rulesCollection, atLeastOnce()).listIndexes();
        verify(mongoTemplate).createCollection("blocked_dates");
        verify(rulesCollection).createIndex(any(Bson.class), any(IndexOptions.class));
        verify(blockedDatesCollection).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("Accepts an existing compatible unique index without creating a new one")
    void acceptsExistingCompatibleIndex() {
        when(mongoTemplate.collectionExists("blocked_dates")).thenReturn(true);
        when(mongoTemplate.getCollection("blocked_dates")).thenReturn(blockedDatesCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, PROVIDER_DATE_UNIQUE);
        when(blockedDatesCollection.listIndexes()).thenReturn(existing);

        assertThatCode(() -> initializer.ensureUniqueIndex("blocked_dates", List.of("providerId", "date"),
                "providerId_1_date_1")).doesNotThrowAnyException();

        verify(blockedDatesCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
        verify(mongoTemplate, never()).createCollection(anyString());
    }

    @Test
    @DisplayName("Accepts a collection created at the same moment by another instance")
    void acceptsCollectionCreatedConcurrently() {
        when(mongoTemplate.collectionExists("availability_rules")).thenReturn(false, true);
        when(mongoTemplate.createCollection("availability_rules"))
                .thenThrow(new RuntimeException("NamespaceExists: collection already exists"));
        when(mongoTemplate.getCollection("availability_rules")).thenReturn(rulesCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, PROVIDER_UNIQUE);
        when(rulesCollection.listIndexes()).thenReturn(existing);

        assertThatCode(() -> initializer.ensureUniqueIndex("availability_rules", List.of("providerId"),
                "providerId_1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Fails safely when the collection cannot be created")
    void failsWhenCollectionCannotBeCreated() {
        when(mongoTemplate.collectionExists("availability_rules")).thenReturn(false, false);
        when(mongoTemplate.createCollection("availability_rules"))
                .thenThrow(new RuntimeException("not authorized"));

        assertThatThrownBy(() -> initializer.ensureUniqueIndex("availability_rules", List.of("providerId"),
                "providerId_1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Startup failed");
    }

    @Test
    @DisplayName("Fails when an index with the same key exists but is not unique (never drops it)")
    void failsWhenSameKeyIsNotUnique() {
        Document notUnique = new Document("v", 2)
                .append("key", new Document("providerId", 1))
                .append("name", "providerId_1");
        when(mongoTemplate.getCollection("availability_rules")).thenReturn(rulesCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, notUnique);
        when(rulesCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureUniqueIndex("availability_rules", List.of("providerId"),
                "providerId_1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index")
                .hasMessageContaining("Manual migration required");

        verify(rulesCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
        verify(rulesCollection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails when the index name is used by a different key or field order")
    void failsWhenNameUsedByDifferentKey() {
        Document wrongOrder = new Document("v", 2)
                .append("key", new Document("date", 1).append("providerId", 1))
                .append("name", "providerId_1_date_1")
                .append("unique", true);
        when(mongoTemplate.getCollection("blocked_dates")).thenReturn(blockedDatesCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, wrongOrder);
        when(blockedDatesCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureUniqueIndex("blocked_dates", List.of("providerId", "date"),
                "providerId_1_date_1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index");

        verify(blockedDatesCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
        verify(blockedDatesCollection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails when the existing unique index is partial (does not guarantee full uniqueness)")
    void failsWhenIndexIsPartial() {
        Document partial = new Document("v", 2)
                .append("key", new Document("providerId", 1))
                .append("name", "providerId_1")
                .append("unique", true)
                .append("partialFilterExpression", new Document("providerId", new Document("$type", "string")));
        when(mongoTemplate.getCollection("availability_rules")).thenReturn(rulesCollection);
        ListIndexesIterable<Document> existing = indexes(ID_INDEX, partial);
        when(rulesCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(() -> initializer.ensureUniqueIndex("availability_rules", List.of("providerId"),
                "providerId_1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index");
    }

    @Test
    @DisplayName("Fails when the index is still missing after creation")
    void failsWhenIndexMissingAfterCreation() {
        when(mongoTemplate.getCollection("availability_rules")).thenReturn(rulesCollection);
        ListIndexesIterable<Document> before = indexes(ID_INDEX);
        ListIndexesIterable<Document> after = indexes(ID_INDEX);
        when(rulesCollection.listIndexes()).thenReturn(before, after);

        assertThatThrownBy(() -> initializer.ensureUniqueIndex("availability_rules", List.of("providerId"),
                "providerId_1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was not found after creation");

        verify(rulesCollection, times(1)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("Wraps a MongoDB error (for example duplicate data) in a startup failure")
    void wrapsMongoErrorOnCreate() {
        when(mongoTemplate.getCollection("availability_rules")).thenReturn(rulesCollection);
        ListIndexesIterable<Document> before = indexes(ID_INDEX);
        when(rulesCollection.listIndexes()).thenReturn(before);
        when(rulesCollection.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(new RuntimeException("E11000 duplicate key error"));

        assertThatThrownBy(() -> initializer.ensureUniqueIndex("availability_rules", List.of("providerId"),
                "providerId_1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Startup failed");
    }
}

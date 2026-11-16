package com.gamagerecruiters.marketplace.modules.catalog.initializer;

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

/**
 * Unit tests for CategoryIndexInitializer:
 * Verifies non-destructive initialization, fail-fast on incompatible indexes,
 * and collection creation on fresh database.
 */
@ExtendWith(MockitoExtension.class)
class CategoryIndexInitializerTest {

    private static final Document ID_INDEX = new Document("v", 2)
            .append("key", new Document("_id", 1)).append("name", "_id_");

    private static final Document SLUG_UNIQUE = new Document("v", 2)
            .append("key", new Document("slug", 1)).append("name", "slug_1").append("unique", true);

    private static final Document PARENT_ID_INDEX = new Document("v", 2)
            .append("key", new Document("parentId", 1)).append("name", "parentId_1");

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private MongoCollection<Document> categoriesCollection;

    private CategoryIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new CategoryIndexInitializer(mongoTemplate);
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
    @DisplayName("Fresh database: creates the categories collection first, then creates and verifies both indexes")
    void freshDatabaseCreatesCollectionThenIndexes() {
        // Given collection does not exist initially
        when(mongoTemplate.collectionExists("categories")).thenReturn(false);
        when(mongoTemplate.getCollection("categories")).thenReturn(categoriesCollection);

        ListIndexesIterable<Document> empty = indexes(ID_INDEX);
        ListIndexesIterable<Document> afterSlug = indexes(ID_INDEX, SLUG_UNIQUE);
        ListIndexesIterable<Document> beforeParentId = indexes(ID_INDEX, SLUG_UNIQUE);
        ListIndexesIterable<Document> afterParentId = indexes(ID_INDEX, SLUG_UNIQUE, PARENT_ID_INDEX);

        when(categoriesCollection.listIndexes())
                .thenReturn(empty, afterSlug, beforeParentId, afterParentId);

        // When initializing
        assertThatCode(initializer::initializeIndexes).doesNotThrowAnyException();

        // Then verify collection is created and both indexes are added
        InOrder order = inOrder(mongoTemplate, categoriesCollection);
        order.verify(mongoTemplate).createCollection("categories");
        order.verify(categoriesCollection, atLeastOnce()).listIndexes();
        verify(categoriesCollection, times(2)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("Existing compatible indexes are accepted without creating anything")
    void acceptsExistingCompatibleIndexes() {
        when(mongoTemplate.collectionExists("categories")).thenReturn(true);
        when(mongoTemplate.getCollection("categories")).thenReturn(categoriesCollection);

        ListIndexesIterable<Document> allExisting = indexes(ID_INDEX, SLUG_UNIQUE, PARENT_ID_INDEX);
        when(categoriesCollection.listIndexes()).thenReturn(allExisting);

        assertThatCode(initializer::initializeIndexes).doesNotThrowAnyException();

        verify(mongoTemplate, never()).createCollection(anyString());
        verify(categoriesCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("Incompatible slug index (non-unique) throws IllegalStateException and fails startup")
    void nonUniqueSlugIndexThrowsIllegalStateException() {
        when(mongoTemplate.collectionExists("categories")).thenReturn(true);
        when(mongoTemplate.getCollection("categories")).thenReturn(categoriesCollection);

        // Slug index exists but unique is false
        Document nonUniqueSlug = new Document("v", 2)
                .append("key", new Document("slug", 1)).append("name", "slug_1").append("unique", false);

        ListIndexesIterable<Document> existing = indexes(ID_INDEX, nonUniqueSlug);
        when(categoriesCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(initializer::initializeIndexes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index 'slug_1' detected on categories");

        verify(categoriesCollection, never()).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    @DisplayName("Sparse index on slug is rejected as incompatible")
    void sparseSlugIndexThrowsIllegalStateException() {
        when(mongoTemplate.collectionExists("categories")).thenReturn(true);
        when(mongoTemplate.getCollection("categories")).thenReturn(categoriesCollection);

        Document sparseSlug = new Document("v", 2)
                .append("key", new Document("slug", 1))
                .append("name", "slug_1")
                .append("unique", true)
                .append("sparse", true);

        ListIndexesIterable<Document> existing = indexes(ID_INDEX, sparseSlug);
        when(categoriesCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(initializer::initializeIndexes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index 'slug_1' detected on categories");
    }

    @Test
    @DisplayName("Partial filter expression on slug is rejected as incompatible")
    void partialFilterExpressionThrowsIllegalStateException() {
        when(mongoTemplate.collectionExists("categories")).thenReturn(true);
        when(mongoTemplate.getCollection("categories")).thenReturn(categoriesCollection);

        Document partialSlug = new Document("v", 2)
                .append("key", new Document("slug", 1))
                .append("name", "slug_1")
                .append("unique", true)
                .append("partialFilterExpression", new Document("active", true));

        ListIndexesIterable<Document> existing = indexes(ID_INDEX, partialSlug);
        when(categoriesCollection.listIndexes()).thenReturn(existing);

        assertThatThrownBy(initializer::initializeIndexes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index 'slug_1' detected on categories");
    }

    @Test
    @DisplayName("Index creation failure throws IllegalStateException and fails startup")
    void creationFailureThrowsIllegalStateException() {
        when(mongoTemplate.collectionExists("categories")).thenReturn(true);
        when(mongoTemplate.getCollection("categories")).thenReturn(categoriesCollection);
        ListIndexesIterable<Document> initial = indexes(ID_INDEX);
        when(categoriesCollection.listIndexes()).thenReturn(initial);

        when(categoriesCollection.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(new RuntimeException("Duplicate keys detected in collection"));

        assertThatThrownBy(initializer::initializeIndexes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Startup failed: Unable to create or verify required index 'slug_1'");
    }
}

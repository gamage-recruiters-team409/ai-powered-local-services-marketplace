package com.gamagerecruiters.marketplace.modules.completion.initializer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;

import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

@ExtendWith(MockitoExtension.class)
class CompletionIndexInitializerTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private IndexOperations indexOps;

    @Mock
    private MongoCollection<Document> collection;

    private CompletionIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new CompletionIndexInitializer(mongoTemplate);
        lenient().when(mongoTemplate.getCollection("service_completions")).thenReturn(collection);
        lenient().when(mongoTemplate.collectionExists("service_completions")).thenReturn(true);
    }

    @Test
    @DisplayName("Accepts existing index {bookingId: 1} with unique: true (no create, no drop)")
    void shouldAcceptValidExistingIndex() {
        stubListIndexes(List.of(idIndex(), bookingIndex(1, true)));

        assertDoesNotThrow(() -> initializer.initializeIndexes());

        verify(mongoTemplate, never()).indexOps(ServiceCompletion.class);
        verify(collection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Accepts existing index when unique is numeric 1 (driver representation)")
    void shouldAcceptUniqueAsNumericOne() {
        stubListIndexes(List.of(idIndex(), bookingIndex(1, 1)));

        assertDoesNotThrow(() -> initializer.initializeIndexes());

        verify(collection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails startup when bookingId index has no unique flag (dropIndex never called)")
    void shouldFailWhenUniqueIsAbsent() {
        stubListIndexes(List.of(idIndex(), new Document("name", "bookingId_1").append("key", new Document("bookingId", 1))));

        assertIncompatible();
    }

    @Test
    @DisplayName("Fails startup when bookingId index has unique: false (dropIndex never called)")
    void shouldFailWhenUniqueIsFalse() {
        stubListIndexes(List.of(idIndex(), bookingIndex(1, false)));

        assertIncompatible();
    }

    @Test
    @DisplayName("Fails startup when bookingId index is unique but descending")
    void shouldFailWhenKeyDirectionIsDescending() {
        stubListIndexes(List.of(idIndex(),
                new Document("name", "bookingId_-1").append("key", new Document("bookingId", -1)).append("unique", true)));

        assertIncompatible();
    }

    @Test
    @DisplayName("Fails startup when bookingId index is unique but partial (does not cover all documents)")
    void shouldFailWhenIndexIsPartial() {
        stubListIndexes(List.of(idIndex(), bookingIndex(1, true)
                .append("partialFilterExpression", new Document("bookingId", new Document("$type", "string")))));

        assertIncompatible();
    }

    @Test
    @DisplayName("Fails startup when bookingId index is unique but sparse")
    void shouldFailWhenIndexIsSparse() {
        stubListIndexes(List.of(idIndex(), bookingIndex(1, true).append("sparse", true)));

        assertIncompatible();
    }

    @Test
    @DisplayName("Fails startup when an index named bookingId_1 is on a different key")
    void shouldFailWhenExpectedNameHasWrongKey() {
        stubListIndexes(List.of(idIndex(),
                new Document("name", "bookingId_1").append("key", new Document("customerId", 1)).append("unique", true)));

        assertIncompatible();
    }

    @Test
    @DisplayName("Creates the index when missing, then verifies the stored definition")
    @SuppressWarnings("removal")
    void shouldCreateIndexWhenMissingAndVerifyStoredDefinition() {
        stubListIndexes(List.of(idIndex()), List.of(idIndex(), bookingIndex(1, true)));
        when(mongoTemplate.indexOps(ServiceCompletion.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("bookingId_1");

        assertDoesNotThrow(() -> initializer.initializeIndexes());

        verify(indexOps, times(1)).ensureIndex(any());
        verify(collection, times(2)).listIndexes();
        verify(collection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails startup when the created index cannot be found when read back")
    @SuppressWarnings("removal")
    void shouldFailWhenCreatedIndexIsNotStored() {
        stubListIndexes(List.of(idIndex()), List.of(idIndex()));
        when(mongoTemplate.indexOps(ServiceCompletion.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("bookingId_1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> initializer.initializeIndexes());
        assertTrue(ex.getMessage().contains("was not found when the stored indexes were read back"));
    }

    // ------------------------------------------------- fresh database / missing collection

    @Test
    @DisplayName("Fresh database: creates the missing service_completions collection before reading its indexes")
    @SuppressWarnings("removal")
    void shouldCreateMissingCollectionBeforeReadingIndexes() {
        when(mongoTemplate.collectionExists("service_completions")).thenReturn(false);
        when(mongoTemplate.indexOps(ServiceCompletion.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("bookingId_1");
        stubListIndexes(List.of(idIndex()), List.of(idIndex(), bookingIndex(1, true)));

        assertDoesNotThrow(() -> initializer.initializeIndexes());

        InOrder order = inOrder(mongoTemplate, collection);
        order.verify(mongoTemplate).createCollection("service_completions");
        order.verify(collection, times(2)).listIndexes();
        verify(collection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Existing collection is reused (createCollection never called)")
    void shouldNotCreateCollectionWhenItExists() {
        stubListIndexes(List.of(idIndex(), bookingIndex(1, true)));

        assertDoesNotThrow(() -> initializer.initializeIndexes());

        verify(mongoTemplate, never()).createCollection(anyString());
    }

    @Test
    @DisplayName("Continues when the collection is created concurrently by another instance")
    void shouldContinueWhenCollectionCreatedConcurrently() {
        when(mongoTemplate.collectionExists("service_completions")).thenReturn(false, true);
        when(mongoTemplate.createCollection("service_completions")).thenThrow(new RuntimeException("NamespaceExists"));
        stubListIndexes(List.of(idIndex(), bookingIndex(1, true)));

        assertDoesNotThrow(() -> initializer.initializeIndexes());

        verify(collection, never()).dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails startup when the missing collection cannot be created")
    void shouldFailWhenCollectionCannotBeCreated() {
        when(mongoTemplate.collectionExists("service_completions")).thenReturn(false, false);
        when(mongoTemplate.createCollection("service_completions")).thenThrow(new RuntimeException("not authorized"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> initializer.initializeIndexes());
        assertTrue(ex.getMessage().contains("unable to create required collection 'service_completions'"));
        verify(collection, never()).listIndexes();
        verify(collection, never()).dropIndex(anyString());
    }

    // ----------------------------------------------------------------- helpers

    private void assertIncompatible() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> initializer.initializeIndexes());
        assertTrue(ex.getMessage().contains("Incompatible index"));
        assertTrue(ex.getMessage().contains("Automatic drop is disabled"));
        verify(mongoTemplate, never()).indexOps(ServiceCompletion.class);
        verify(collection, never()).dropIndex(anyString());
    }

    private static Document idIndex() {
        return new Document("name", "_id_").append("key", new Document("_id", 1));
    }

    private static Document bookingIndex(int direction, Object unique) {
        return new Document("name", "bookingId_1")
                .append("key", new Document("bookingId", direction))
                .append("unique", unique);
    }

    /** Each list is what one call to listIndexes() returns, in call order. */
    @SafeVarargs
    private void stubListIndexes(List<Document>... calls) {
        List<ListIndexesIterable<Document>> iterables = new ArrayList<>();
        for (List<Document> call : calls) {
            iterables.add(iterableOf(call));
        }
        OngoingStubbing<ListIndexesIterable<Document>> stubbing = when(collection.listIndexes());
        for (ListIndexesIterable<Document> iterable : iterables) {
            stubbing = stubbing.thenReturn(iterable);
        }
    }

    @SuppressWarnings("unchecked")
    private static ListIndexesIterable<Document> iterableOf(List<Document> indexes) {
        ListIndexesIterable<Document> iterable = mock(ListIndexesIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        Iterator<Document> source = indexes.iterator();
        lenient().when(iterable.iterator()).thenReturn(cursor);
        lenient().when(cursor.hasNext()).thenAnswer(invocation -> source.hasNext());
        lenient().when(cursor.next()).thenAnswer(invocation -> source.next());
        return iterable;
    }
}

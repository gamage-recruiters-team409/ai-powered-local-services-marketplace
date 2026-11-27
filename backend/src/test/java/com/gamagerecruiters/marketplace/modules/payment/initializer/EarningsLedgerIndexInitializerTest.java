package com.gamagerecruiters.marketplace.modules.payment.initializer;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies creation and fail-closed validation of the provider
 * earnings history index without modifying existing financial indexes.
 */
class EarningsLedgerIndexInitializerTest {

    private static final String COLLECTION =
            EarningsLedgerIndexInitializer.LEDGER_COLLECTION;

    private static final String HISTORY_INDEX =
            EarningsLedgerIndexInitializer.PROVIDER_HISTORY_INDEX;

    private MongoCollection<Document> collection;
    private EarningsLedgerIndexInitializer initializer;
    private List<Document> storedIndexes;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {

        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        collection = mock(MongoCollection.class);

        ListIndexesIterable<Document> indexIterable =
                mock(ListIndexesIterable.class);

        initializer = new EarningsLedgerIndexInitializer(mongoTemplate);
        storedIndexes = new ArrayList<>();

        when(mongoTemplate.collectionExists(COLLECTION))
                .thenReturn(true);

        when(mongoTemplate.getCollection(COLLECTION))
                .thenReturn(collection);

        when(collection.listIndexes())
                .thenReturn(indexIterable);

        when(indexIterable.into(
                org.mockito.ArgumentMatchers.<List<Document>>any()))
                .thenAnswer(invocation -> {
                    List<Document> target = invocation.getArgument(0);
                    target.addAll(storedIndexes);
                    return target;
                });

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

                    storedIndexes.add(metadata);

                    return options.getName();
                });
    }

    private Document historyKey() {
        return new Document("providerId", 1)
                .append("currency", 1)
                .append("sequenceNumber", -1)
                .append("_id", -1);
    }

    private Document historyIndex() {
        return new Document("name", HISTORY_INDEX)
                .append("key", historyKey());
    }

    private void assertRejectedWithoutModification() {

        assertThrows(
                IllegalStateException.class,
                initializer::ensureProviderHistoryIndex);

        verify(collection, never())
                .createIndex(any(Bson.class), any(IndexOptions.class));

        verify(collection, never()).dropIndex(anyString());
        verify(collection, never()).dropIndexes();
    }

    @Test
    void createsExpectedHistoryIndex() {

        initializer.ensureProviderHistoryIndex();

        ArgumentCaptor<Bson> keyCaptor =
                ArgumentCaptor.forClass(Bson.class);

        ArgumentCaptor<IndexOptions> optionsCaptor =
                ArgumentCaptor.forClass(IndexOptions.class);

        verify(collection).createIndex(
                keyCaptor.capture(),
                optionsCaptor.capture());

        Document actualKey = (Document) keyCaptor.getValue();

        assertEquals(historyKey(), actualKey);

        assertEquals(
                List.of("providerId", "currency", "sequenceNumber", "_id"),
                new ArrayList<>(actualKey.keySet()));

        IndexOptions options = optionsCaptor.getValue();

        assertEquals(HISTORY_INDEX, options.getName());
        assertFalse(options.isUnique());
        assertFalse(options.isSparse());
        assertNull(options.getPartialFilterExpression());
    }

    @Test
    void reusesCompatibleExistingIndex() {

        storedIndexes.add(historyIndex());

        initializer.ensureProviderHistoryIndex();

        verify(collection, never())
                .createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    void repeatedInitializationDoesNotCreateDuplicateIndex() {

        initializer.ensureProviderHistoryIndex();
        initializer.ensureProviderHistoryIndex();

        verify(collection, times(1))
                .createIndex(any(Bson.class), any(IndexOptions.class));

        assertEquals(1, storedIndexes.size());
    }

    @Test
    void rejectsIncorrectFieldOrder() {

        storedIndexes.add(historyIndex().append(
                "key",
                new Document("currency", 1)
                        .append("providerId", 1)
                        .append("sequenceNumber", -1)
                        .append("_id", -1)));

        assertRejectedWithoutModification();
    }

    @Test
    void rejectsIncorrectSortDirection() {

        storedIndexes.add(historyIndex().append(
                "key",
                historyKey().append("sequenceNumber", 1)));

        assertRejectedWithoutModification();
    }

    @Test
    void rejectsMissingIndexField() {

        Document key = historyKey();
        key.remove("_id");

        storedIndexes.add(historyIndex().append("key", key));

        assertRejectedWithoutModification();
    }

    @Test
    void rejectsConflictingIndexName() {

        storedIndexes.add(historyIndex().append(
                "name", "unexpected_history_index"));

        assertRejectedWithoutModification();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unique", "sparse", "hidden"})
    void rejectsIncompatibleBooleanOptions(String option) {

        storedIndexes.add(historyIndex().append(option, true));

        assertRejectedWithoutModification();
    }

    @Test
    void rejectsPartialIndex() {

        storedIndexes.add(historyIndex().append(
                "partialFilterExpression",
                new Document("sequenceNumber",
                        new Document("$type", "number"))));

        assertRejectedWithoutModification();
    }

    @Test
    void rejectsTtlIndex() {

        storedIndexes.add(historyIndex().append(
                "expireAfterSeconds", 3600L));

        assertRejectedWithoutModification();
    }

    @Test
    void rejectsUnexpectedCollation() {

        storedIndexes.add(historyIndex().append(
                "collation", new Document("locale", "en")));

        assertRejectedWithoutModification();
    }

    @Test
    void rejectsIndexMissingAfterCreation() {

        when(collection.createIndex(
                any(Bson.class),
                any(IndexOptions.class)))
                .thenReturn(HISTORY_INDEX);

        assertThrows(
                IllegalStateException.class,
                initializer::ensureProviderHistoryIndex);
    }

    @Test
    void preservesExistingFinancialIndexes() {

        Document eventKeyIndex = new Document(
                "name", EarningsLedgerIndexInitializer.EVENT_KEY_INDEX)
                .append("key", new Document("eventKey", 1))
                .append("unique", true);

        Document sequenceIndex = new Document(
                "name", EarningsLedgerIndexInitializer.PROVIDER_SEQUENCE_INDEX)
                .append("key",
                        new Document("providerId", 1)
                                .append("currency", 1)
                                .append("sequenceNumber", 1))
                .append("unique", true)
                .append("partialFilterExpression",
                        new Document("sequenceNumber",
                                new Document("$type", "number")));

        storedIndexes.add(eventKeyIndex);
        storedIndexes.add(sequenceIndex);

        initializer.ensureProviderHistoryIndex();

        assertEquals(3, storedIndexes.size());
        assertTrue(storedIndexes.contains(eventKeyIndex));
        assertTrue(storedIndexes.contains(sequenceIndex));

        verify(collection, never()).dropIndex(anyString());
        verify(collection, never()).dropIndexes();
    }

    private static final String ITEMIZED_INDEX =
            EarningsLedgerIndexInitializer.PROVIDER_ITEMIZED_INDEX;

    private Document itemizedKey() {
        return new Document("providerId", 1)
                .append("currency", 1)
                .append("eventType", 1)
                .append("sequenceNumber", -1)
                .append("_id", -1);
    }

    private Document itemizedIndex() {
        return new Document("name", ITEMIZED_INDEX)
                .append("key", itemizedKey());
    }

    @Test
    void createsItemizedIndexWithExpectedKey() {

        initializer.ensureProviderItemizedIndex();

        ArgumentCaptor<Bson> keyCaptor =
                ArgumentCaptor.forClass(Bson.class);

        ArgumentCaptor<IndexOptions> optionsCaptor =
                ArgumentCaptor.forClass(IndexOptions.class);

        verify(collection).createIndex(
                keyCaptor.capture(), optionsCaptor.capture());

        Document key = (Document) keyCaptor.getValue();

        assertEquals(itemizedKey(), key);

        assertEquals(
                new ArrayList<>(itemizedKey().keySet()),
                new ArrayList<>(key.keySet()));

        assertEquals(ITEMIZED_INDEX,
                optionsCaptor.getValue().getName());

        assertFalse(optionsCaptor.getValue().isUnique());
    }

    @Test
    void reusesCompatibleItemizedIndex() {

        storedIndexes.add(itemizedIndex());

        initializer.ensureProviderItemizedIndex();

        verify(collection, never())
                .createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    void itemizedIndexInitializationIsIdempotent() {

        initializer.ensureProviderItemizedIndex();
        initializer.ensureProviderItemizedIndex();

        assertEquals(1, storedIndexes.size());

        verify(collection, times(1))
                .createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    void rejectsItemizedIndexWithIncorrectFieldOrder() {

        Document key = new Document("providerId", 1)
                .append("eventType", 1)
                .append("currency", 1)
                .append("sequenceNumber", -1)
                .append("_id", -1);

        storedIndexes.add(itemizedIndex().append("key", key));

        assertItemizedIndexRejected();
    }

    @Test
    void rejectsItemizedIndexWithIncorrectDirection() {

        storedIndexes.add(itemizedIndex().append(
                "key", itemizedKey().append("sequenceNumber", 1)));

        assertItemizedIndexRejected();
    }

    @Test
    void rejectsItemizedIndexWithoutEventType() {

        Document key = itemizedKey();
        key.remove("eventType");

        storedIndexes.add(itemizedIndex().append("key", key));

        assertItemizedIndexRejected();
    }

    @Test
    void rejectsItemizedIndexWithConflictingName() {

        storedIndexes.add(itemizedIndex().append(
                "name", "unexpected_itemized_index"));

        assertItemizedIndexRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unique", "sparse", "hidden"})
    void rejectsIncompatibleItemizedBooleanOptions(String option) {

        storedIndexes.add(itemizedIndex().append(option, true));

        assertItemizedIndexRejected();
    }

    @Test
    void rejectsPartialItemizedIndex() {

        storedIndexes.add(itemizedIndex().append(
                "partialFilterExpression",
                new Document("eventType", "EARNING_PENDING")));

        assertItemizedIndexRejected();
    }

    @Test
    void rejectsTtlItemizedIndex() {

        storedIndexes.add(itemizedIndex().append(
                "expireAfterSeconds", 3600L));

        assertItemizedIndexRejected();
    }

    @Test
    void rejectsItemizedIndexWithUnexpectedCollation() {

        storedIndexes.add(itemizedIndex().append(
                "collation", new Document("locale", "en")));

        assertItemizedIndexRejected();
    }

    @Test
    void rejectsItemizedIndexMissingAfterCreation() {

        when(collection.createIndex(
                any(Bson.class), any(IndexOptions.class)))
                .thenReturn(ITEMIZED_INDEX);

        assertThrows(
                IllegalStateException.class,
                initializer::ensureProviderItemizedIndex);
    }

    @Test
    void itemizedIndexPreservesExistingHistoryIndex() {

        Document existingHistory = historyIndex();

        storedIndexes.add(existingHistory);

        initializer.ensureProviderItemizedIndex();

        assertEquals(2, storedIndexes.size());
        assertTrue(storedIndexes.contains(existingHistory));

        verify(collection, never()).dropIndex(anyString());
        verify(collection, never()).dropIndexes();
    }

    /**
     * Verifies that incompatible indexes are rejected without mutation.
     */
    private void assertItemizedIndexRejected() {

        assertThrows(
                IllegalStateException.class,
                initializer::ensureProviderItemizedIndex);

        verify(collection, never())
                .createIndex(any(Bson.class), any(IndexOptions.class));

        verify(collection, never()).dropIndex(anyString());
        verify(collection, never()).dropIndexes();
    }

}

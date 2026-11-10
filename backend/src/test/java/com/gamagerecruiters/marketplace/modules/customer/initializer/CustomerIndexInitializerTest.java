package com.gamagerecruiters.marketplace.modules.customer.initializer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;

import com.gamagerecruiters.marketplace.modules.customer.domain.CustomerAddress;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;

@ExtendWith(MockitoExtension.class)
class CustomerIndexInitializerTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private IndexOperations addressIndexOps;

    @Mock
    @SuppressWarnings("rawtypes")
    private MongoCollection addressCollection;

    @Mock
    @SuppressWarnings("rawtypes")
    private AggregateIterable aggregateIterable;

    private CustomerIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new CustomerIndexInitializer(mongoTemplate);
    }

    @Test
    @DisplayName("Fresh collection: Creates all 3 required indexes and never calls dropIndex")
    @SuppressWarnings("unchecked")
    void shouldCreateAllThreeIndexesOnFreshCollection() {
        when(mongoTemplate.getCollection("customer_addresses")).thenReturn(addressCollection);
        when(addressCollection.aggregate(any())).thenReturn(aggregateIterable);
        when(aggregateIterable.first()).thenReturn(null);

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of());
        when(addressCollection.listIndexes()).thenReturn(iterable);
        when(mongoTemplate.indexOps(CustomerAddress.class)).thenReturn(addressIndexOps);

        assertDoesNotThrow(() -> initializer.initializeIndexes());

        verify(addressIndexOps, times(3)).ensureIndex(any());
        verify(addressCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Extra compound index present: Startup succeeds and unrelated compound index is ignored")
    @SuppressWarnings("unchecked")
    void shouldIgnoreUnrelatedCompoundIndex() {
        when(mongoTemplate.getCollection("customer_addresses")).thenReturn(addressCollection);
        when(addressCollection.aggregate(any())).thenReturn(aggregateIterable);
        when(aggregateIterable.first()).thenReturn(null);

        Document extraCompound = new Document("name", "customerId_1_createdAt_1")
                .append("key", new Document("customerId", 1).append("createdAt", 1))
                .append("unique", false);

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(extraCompound));
        when(addressCollection.listIndexes()).thenReturn(iterable);
        when(mongoTemplate.indexOps(CustomerAddress.class)).thenReturn(addressIndexOps);

        assertDoesNotThrow(() -> initializer.initializeIndexes());

        verify(addressIndexOps, times(3)).ensureIndex(any());
        verify(addressCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("All required indexes already present and compatible: Verifies without recreating or dropping")
    @SuppressWarnings("unchecked")
    void shouldAcceptAllCompatibleExistingIndexes() {
        when(mongoTemplate.getCollection("customer_addresses")).thenReturn(addressCollection);
        when(addressCollection.aggregate(any())).thenReturn(aggregateIterable);
        when(aggregateIterable.first()).thenReturn(null);

        Document plainIndex = new Document("name", "customerId_1")
                .append("key", new Document("customerId", 1))
                .append("unique", false);

        Document defaultIndex = new Document("name", "customerId_1_isDefault_1_unique_default")
                .append("key", new Document("customerId", 1).append("isDefault", 1))
                .append("unique", true)
                .append("partialFilterExpression", new Document("isDefault", true));

        Document slotIndex = new Document("name", "customerId_1_slot_1_unique")
                .append("key", new Document("customerId", 1).append("slot", 1))
                .append("unique", true)
                .append("partialFilterExpression", new Document("slot", new Document("$gt", 0)));

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(plainIndex, defaultIndex, slotIndex));
        when(addressCollection.listIndexes()).thenReturn(iterable);

        assertDoesNotThrow(() -> initializer.initializeIndexes());

        verify(mongoTemplate, never()).indexOps(CustomerAddress.class);
        verify(addressCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Incompatible unique index on customerId: Fails startup and never drops index")
    @SuppressWarnings("unchecked")
    void shouldFailStartupWhenIncompatibleUniquePlainIndexExists() {
        when(mongoTemplate.getCollection("customer_addresses")).thenReturn(addressCollection);
        when(addressCollection.aggregate(any())).thenReturn(aggregateIterable);
        when(aggregateIterable.first()).thenReturn(null);

        Document badIndex = new Document("name", "customerId_unique")
                .append("key", new Document("customerId", 1))
                .append("unique", true);

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(badIndex));
        when(addressCollection.listIndexes()).thenReturn(iterable);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> initializer.initializeIndexes()
        );
        assertTrue(ex.getMessage().contains("Incompatible index 'customerId_unique' detected"));
        verify(addressCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Incompatible default address index (missing partialFilter): Fails startup and never drops")
    @SuppressWarnings("unchecked")
    void shouldFailStartupWhenIncompatibleDefaultAddressIndexExists() {
        when(mongoTemplate.getCollection("customer_addresses")).thenReturn(addressCollection);
        when(addressCollection.aggregate(any())).thenReturn(aggregateIterable);
        when(aggregateIterable.first()).thenReturn(null);

        Document plainIndex = new Document("name", "customerId_1")
                .append("key", new Document("customerId", 1))
                .append("unique", false);

        Document badDefaultIndex = new Document("name", "customerId_1_isDefault_1_unique_default")
                .append("key", new Document("customerId", 1).append("isDefault", 1))
                .append("unique", true); // Missing partialFilterExpression!

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(plainIndex, badDefaultIndex));
        when(addressCollection.listIndexes()).thenReturn(iterable);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> initializer.initializeIndexes()
        );
        assertTrue(ex.getMessage().contains("Incompatible index 'customerId_1_isDefault_1_unique_default' detected"));
        verify(addressCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Pre-check finds duplicate default addresses: Fails startup with migration guide hint")
    @SuppressWarnings("unchecked")
    void shouldFailStartupWhenDuplicateDefaultsExistInPreCheck() {
        when(mongoTemplate.getCollection("customer_addresses")).thenReturn(addressCollection);
        when(addressCollection.aggregate(any())).thenReturn(aggregateIterable);
        when(aggregateIterable.first()).thenReturn(new Document("_id", "cust-123").append("count", 2));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> initializer.initializeIndexes()
        );
        assertTrue(ex.getMessage().contains("Customer 'cust-123' has 2 default addresses"));
        assertTrue(ex.getMessage().contains("2026-11-customer-default-address-cleanup.md"));
        verify(addressCollection, never()).dropIndex(any(String.class));
    }

    @SuppressWarnings("unchecked")
    private ListIndexesIterable<Document> mockListIndexesIterable(List<Document> indexes) {
        ListIndexesIterable<Document> iterable = mock(ListIndexesIterable.class);
        when(iterable.iterator()).thenAnswer(inv -> {
            com.mongodb.client.MongoCursor<Document> cursor = mock(com.mongodb.client.MongoCursor.class);
            java.util.Iterator<Document> it = indexes.iterator();
            when(cursor.hasNext()).thenAnswer(i -> it.hasNext());
            if (!indexes.isEmpty()) {
                when(cursor.next()).thenAnswer(i -> it.next());
            }
            return cursor;
        });
        return iterable;
    }
}

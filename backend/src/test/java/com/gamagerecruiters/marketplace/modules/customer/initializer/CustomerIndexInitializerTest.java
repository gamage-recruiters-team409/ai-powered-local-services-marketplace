package com.gamagerecruiters.marketplace.modules.customer.initializer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

@ExtendWith(MockitoExtension.class)
class CustomerIndexInitializerTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private IndexOperations addressIndexOps;

    @Mock
    @SuppressWarnings("rawtypes")
    private MongoCollection addressCollection;

    private CustomerIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new CustomerIndexInitializer(mongoTemplate);
    }

    @Test
    @DisplayName("Creates customerId_1 index when missing and never calls dropIndex")
    @SuppressWarnings("unchecked")
    void shouldCreateCustomerIdIndexWhenMissing() {
        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of());
        when(mongoTemplate.getCollection("customer_addresses")).thenReturn(addressCollection);
        when(addressCollection.listIndexes()).thenReturn(iterable);
        when(mongoTemplate.indexOps(CustomerAddress.class)).thenReturn(addressIndexOps);
        when(addressIndexOps.ensureIndex(any())).thenReturn("customerId_1");

        assertDoesNotThrow(() -> initializer.initCustomerAddressesIndex());

        verify(addressIndexOps).ensureIndex(any());
        verify(addressCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Validates existing customerId_1 index when compatible")
    @SuppressWarnings("unchecked")
    void shouldAcceptCompatibleExistingIndex() {
        Document existingIndex = new Document("name", "customerId_1")
                .append("key", new Document("customerId", 1))
                .append("unique", false);

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(existingIndex));
        when(mongoTemplate.getCollection("customer_addresses")).thenReturn(addressCollection);
        when(addressCollection.listIndexes()).thenReturn(iterable);

        assertDoesNotThrow(() -> initializer.initCustomerAddressesIndex());

        verify(mongoTemplate, never()).indexOps(CustomerAddress.class);
        verify(addressCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Fails startup when incompatible unique index exists on customerId")
    @SuppressWarnings("unchecked")
    void shouldFailStartupWhenIncompatibleUniqueIndexExists() {
        Document badIndex = new Document("name", "customerId_unique")
                .append("key", new Document("customerId", 1))
                .append("unique", true);

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(badIndex));
        when(mongoTemplate.getCollection("customer_addresses")).thenReturn(addressCollection);
        when(addressCollection.listIndexes()).thenReturn(iterable);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> initializer.initCustomerAddressesIndex()
        );
        assertTrue(ex.getMessage().contains("Incompatible index 'customerId_unique' detected"));
        verify(addressCollection, never()).dropIndex(any(String.class));
    }

    @SuppressWarnings("unchecked")
    private ListIndexesIterable<Document> mockListIndexesIterable(List<Document> indexes) {
        ListIndexesIterable<Document> iterable = mock(ListIndexesIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);

        when(iterable.iterator()).thenReturn(cursor);

        if (indexes.isEmpty()) {
            when(cursor.hasNext()).thenReturn(false);
        } else if (indexes.size() == 1) {
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(indexes.get(0));
        } else {
            Boolean[] hasNextResults = new Boolean[indexes.size() + 1];
            for (int i = 0; i < indexes.size(); i++) {
                hasNextResults[i] = true;
            }
            hasNextResults[indexes.size()] = false;
            when(cursor.hasNext()).thenReturn(hasNextResults[0],
                    java.util.Arrays.copyOfRange(hasNextResults, 1, hasNextResults.length));
            when(cursor.next()).thenReturn(indexes.get(0),
                    indexes.subList(1, indexes.size()).toArray(new Document[0]));
        }

        return iterable;
    }
}

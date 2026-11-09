package com.gamagerecruiters.marketplace.modules.auth.initializer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

@ExtendWith(MockitoExtension.class)
class MongoIndexInitializerTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private IndexOperations userIndexOps;

    @Mock
    private IndexOperations authTokenIndexOps;

    @Mock
    @SuppressWarnings("rawtypes")
    private MongoCollection usersCollection;

    private MongoIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new MongoIndexInitializer(mongoTemplate);
    }

    @Test
    @DisplayName("Issue C: Fails startup when legacy non-partial phone index is detected (never drops)")
    @SuppressWarnings("unchecked")
    void shouldFailOnLegacyNonPartialPhoneIndex() {
        // Set up email index (success)
        when(mongoTemplate.indexOps(User.class)).thenReturn(userIndexOps);
        when(userIndexOps.ensureIndex(any())).thenReturn("email_1");

        // Set up users collection with legacy non-partial index
        when(mongoTemplate.getCollection("users")).thenReturn(usersCollection);

        Document legacyIndex = new Document()
                .append("name", "phone_1")
                .append("key", new Document("phone", 1));
        // No partialFilterExpression -> legacy

        ListIndexesIterable<Document> listIndexesIterable = mockListIndexesIterable(List.of(legacyIndex));
        when(usersCollection.listIndexes()).thenReturn(listIndexesIterable);

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::initializeIndexes);
        assertTrue(ex.getMessage().contains("Legacy non-partial index"));
        assertTrue(ex.getMessage().contains("Manual migration required"));
        assertTrue(ex.getMessage().contains("2026-11-users-phone-partial-index.md"));

        // Verify dropIndex is NEVER called
        verify(usersCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Issue C: Succeeds when correct partial phone index already exists")
    @SuppressWarnings("unchecked")
    void shouldSucceedWhenCorrectPartialPhoneIndexExists() {
        when(mongoTemplate.indexOps(User.class)).thenReturn(userIndexOps);
        when(userIndexOps.ensureIndex(any())).thenReturn("email_1");
        when(mongoTemplate.indexOps(AuthToken.class)).thenReturn(authTokenIndexOps);
        when(authTokenIndexOps.ensureIndex(any())).thenReturn("tokenHash_1").thenReturn("expiresAt_1");

        when(mongoTemplate.getCollection("users")).thenReturn(usersCollection);

        Document correctIndex = new Document()
                .append("name", "phone_1")
                .append("key", new Document("phone", 1))
                .append("partialFilterExpression", new Document("phone", new Document("$type", "string")));

        ListIndexesIterable<Document> listIndexesIterable = mockListIndexesIterable(List.of(correctIndex));
        when(usersCollection.listIndexes()).thenReturn(listIndexesIterable);

        assertDoesNotThrow(() -> initializer.initializeIndexes());

        // Verify dropIndex is NEVER called
        verify(usersCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Issue C: Creates phone index when no phone index exists")
    @SuppressWarnings("unchecked")
    void shouldCreatePhoneIndexWhenNoneExists() {
        when(mongoTemplate.indexOps(User.class)).thenReturn(userIndexOps);
        when(userIndexOps.ensureIndex(any())).thenReturn("email_1").thenReturn("phone_1");
        when(mongoTemplate.indexOps(AuthToken.class)).thenReturn(authTokenIndexOps);
        when(authTokenIndexOps.ensureIndex(any())).thenReturn("tokenHash_1").thenReturn("expiresAt_1");

        when(mongoTemplate.getCollection("users")).thenReturn(usersCollection);

        // Only the _id index — no phone index at all
        Document idIndex = new Document()
                .append("name", "_id_")
                .append("key", new Document("_id", 1));

        ListIndexesIterable<Document> listIndexesIterable = mockListIndexesIterable(List.of(idIndex));
        when(usersCollection.listIndexes()).thenReturn(listIndexesIterable);

        assertDoesNotThrow(() -> initializer.initializeIndexes());

        verify(usersCollection, never()).dropIndex(any(String.class));
    }

    @SuppressWarnings("unchecked")
    private ListIndexesIterable<Document> mockListIndexesIterable(List<Document> indexes) {
        ListIndexesIterable<Document> iterable = mock(ListIndexesIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);

        // Support for-each iteration
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

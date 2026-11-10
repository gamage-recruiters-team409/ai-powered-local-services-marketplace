package com.gamagerecruiters.marketplace.modules.auth.initializer;

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

    private void setupBaseMocks() {
        when(mongoTemplate.indexOps(User.class)).thenReturn(userIndexOps);
        when(userIndexOps.ensureIndex(any())).thenReturn("email_1");
        when(mongoTemplate.getCollection("users")).thenReturn(usersCollection);
    }

    @Test
    @DisplayName("Finding 1: Fails startup when partial filter matches but unique is absent (dropIndex never called)")
    @SuppressWarnings("unchecked")
    void shouldFailWhenPartialFilterMatchesButUniqueIsAbsent() {
        setupBaseMocks();

        Document indexWithoutUnique = new Document()
                .append("name", "phone_1")
                .append("key", new Document("phone", 1))
                .append("partialFilterExpression", new Document("phone", new Document("$type", "string")));
        // unique flag is absent

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(indexWithoutUnique));
        when(usersCollection.listIndexes()).thenReturn(iterable);

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::initializeIndexes);
        assertTrue(ex.getMessage().contains("Incompatible index"));
        assertTrue(ex.getMessage().contains("Manual migration required"));
        assertTrue(ex.getMessage().contains("2026-11-users-phone-partial-index.md"));

        verify(usersCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Finding 1: Fails startup when partial filter matches but unique=false (dropIndex never called)")
    @SuppressWarnings("unchecked")
    void shouldFailWhenPartialFilterMatchesButUniqueIsFalse() {
        setupBaseMocks();

        Document indexWithUniqueFalse = new Document()
                .append("name", "phone_1")
                .append("key", new Document("phone", 1))
                .append("unique", false)
                .append("partialFilterExpression", new Document("phone", new Document("$type", "string")));

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(indexWithUniqueFalse));
        when(usersCollection.listIndexes()).thenReturn(iterable);

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::initializeIndexes);
        assertTrue(ex.getMessage().contains("Incompatible index"));
        assertTrue(ex.getMessage().contains("Manual migration required"));

        verify(usersCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Finding 1: Fails startup when unique=true but wrong key direction (e.g. descending -1)")
    @SuppressWarnings("unchecked")
    void shouldFailWhenUniqueTrueButWrongKeyDirection() {
        setupBaseMocks();

        Document wrongKeyIndex = new Document()
                .append("name", "phone_-1")
                .append("key", new Document("phone", -1))
                .append("unique", true)
                .append("partialFilterExpression", new Document("phone", new Document("$type", "string")));

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(wrongKeyIndex));
        when(usersCollection.listIndexes()).thenReturn(iterable);

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::initializeIndexes);
        assertTrue(ex.getMessage().contains("Incompatible index"));
        verify(usersCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Finding 1: Fails startup when unique=true but wrong partial filter expression")
    @SuppressWarnings("unchecked")
    void shouldFailWhenUniqueTrueButWrongFilter() {
        setupBaseMocks();

        Document wrongFilterIndex = new Document()
                .append("name", "phone_1")
                .append("key", new Document("phone", 1))
                .append("unique", true)
                .append("partialFilterExpression", new Document("phone", new Document("$exists", true)));

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(wrongFilterIndex));
        when(usersCollection.listIndexes()).thenReturn(iterable);

        IllegalStateException ex = assertThrows(IllegalStateException.class, initializer::initializeIndexes);
        assertTrue(ex.getMessage().contains("Incompatible index"));
        verify(usersCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Finding 1: Accepts existing index when unique=true, correct key {phone: 1}, and correct partial filter")
    @SuppressWarnings("unchecked")
    void shouldAcceptWhenUniqueTrueCorrectKeyAndFilter() {
        setupBaseMocks();
        when(mongoTemplate.indexOps(AuthToken.class)).thenReturn(authTokenIndexOps);
        when(authTokenIndexOps.ensureIndex(any())).thenReturn("tokenHash_1").thenReturn("expiresAt_1");

        Document correctIndex = new Document()
                .append("name", "phone_1")
                .append("key", new Document("phone", 1))
                .append("unique", true)
                .append("partialFilterExpression", new Document("phone", new Document("$type", "string")));

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(correctIndex));
        when(usersCollection.listIndexes()).thenReturn(iterable);

        assertDoesNotThrow(() -> initializer.initializeIndexes());
        verify(usersCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Finding 1: Accepts existing index when unique is numeric 1 (driver representation)")
    @SuppressWarnings("unchecked")
    void shouldAcceptWhenUniqueIsNumericOne() {
        setupBaseMocks();
        when(mongoTemplate.indexOps(AuthToken.class)).thenReturn(authTokenIndexOps);
        when(authTokenIndexOps.ensureIndex(any())).thenReturn("tokenHash_1").thenReturn("expiresAt_1");

        Document correctIndex = new Document()
                .append("name", "phone_1")
                .append("key", new Document("phone", 1))
                .append("unique", 1)
                .append("partialFilterExpression", new Document("phone", new Document("$type", "string")));

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(correctIndex));
        when(usersCollection.listIndexes()).thenReturn(iterable);

        assertDoesNotThrow(() -> initializer.initializeIndexes());
        verify(usersCollection, never()).dropIndex(any(String.class));
    }

    @Test
    @DisplayName("Finding 1: Creates partial unique phone index when no phone index exists")
    @SuppressWarnings("unchecked")
    void shouldCreatePhoneIndexWhenNoneExists() {
        setupBaseMocks();
        when(userIndexOps.ensureIndex(any())).thenReturn("email_1").thenReturn("phone_1");
        when(mongoTemplate.indexOps(AuthToken.class)).thenReturn(authTokenIndexOps);
        when(authTokenIndexOps.ensureIndex(any())).thenReturn("tokenHash_1").thenReturn("expiresAt_1");

        Document idIndex = new Document()
                .append("name", "_id_")
                .append("key", new Document("_id", 1));

        ListIndexesIterable<Document> iterable = mockListIndexesIterable(List.of(idIndex));
        when(usersCollection.listIndexes()).thenReturn(iterable);

        assertDoesNotThrow(() -> initializer.initializeIndexes());
        verify(usersCollection, never()).dropIndex(any(String.class));
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

package com.gamagerecruiters.marketplace.modules.provider.initializer;

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
class ProviderProfileIndexInitializerTest {

    private static final Document ID_INDEX = new Document("v", 2)
            .append("key", new Document("_id", 1))
            .append("name", "_id_");

    private static final Document USER_ID_UNIQUE = new Document("v", 2)
            .append("key", new Document("userId", 1))
            .append("name", "userId_1")
            .append("unique", true);

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private MongoCollection<Document> providerProfilesCollection;

    private ProviderProfileIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new ProviderProfileIndexInitializer(mongoTemplate);
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
    @DisplayName("Fresh database creates collection and unique userId index")
    void freshDatabaseCreatesCollectionAndIndex() {
        when(mongoTemplate.collectionExists("provider_profiles"))
                .thenReturn(false);

        when(mongoTemplate.getCollection("provider_profiles"))
                .thenReturn(providerProfilesCollection);

        ListIndexesIterable<Document> before = indexes(ID_INDEX);
        ListIndexesIterable<Document> after = indexes(ID_INDEX, USER_ID_UNIQUE);

        when(providerProfilesCollection.listIndexes())
                .thenReturn(before, after);

        assertThatCode(initializer::initializeIndexes)
                .doesNotThrowAnyException();

        InOrder order = inOrder(
                mongoTemplate,
                providerProfilesCollection
        );

        order.verify(mongoTemplate)
                .createCollection("provider_profiles");

        order.verify(providerProfilesCollection, atLeastOnce())
                .listIndexes();

        verify(providerProfilesCollection)
                .createIndex(
                        any(Bson.class),
                        any(IndexOptions.class)
                );
    }

    @Test
    @DisplayName("Accepts existing compatible unique userId index")
    void acceptsExistingCompatibleIndex() {
        when(mongoTemplate.collectionExists("provider_profiles"))
                .thenReturn(true);

        when(mongoTemplate.getCollection("provider_profiles"))
                .thenReturn(providerProfilesCollection);

        ListIndexesIterable<Document> existing =
                indexes(ID_INDEX, USER_ID_UNIQUE);

        when(providerProfilesCollection.listIndexes())
                .thenReturn(existing);

        assertThatCode(() ->
                initializer.ensureUniqueIndex(
                        "provider_profiles",
                        List.of("userId"),
                        "userId_1"
                )
        ).doesNotThrowAnyException();

        verify(providerProfilesCollection, never())
                .createIndex(
                        any(Bson.class),
                        any(IndexOptions.class)
                );

        verify(mongoTemplate, never())
                .createCollection(anyString());
    }

    @Test
    @DisplayName("Accepts collection created concurrently")
    void acceptsCollectionCreatedConcurrently() {
        when(mongoTemplate.collectionExists("provider_profiles"))
                .thenReturn(false, true);

        when(mongoTemplate.createCollection("provider_profiles"))
                .thenThrow(
                        new RuntimeException(
                                "NamespaceExists: collection already exists"
                        )
                );

        when(mongoTemplate.getCollection("provider_profiles"))
                .thenReturn(providerProfilesCollection);

        ListIndexesIterable<Document> existing =
                indexes(ID_INDEX, USER_ID_UNIQUE);

        when(providerProfilesCollection.listIndexes())
                .thenReturn(existing);

        assertThatCode(() ->
                initializer.ensureUniqueIndex(
                        "provider_profiles",
                        List.of("userId"),
                        "userId_1"
                )
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Fails safely when collection cannot be created")
    void failsWhenCollectionCannotBeCreated() {
        when(mongoTemplate.collectionExists("provider_profiles"))
                .thenReturn(false, false);

        when(mongoTemplate.createCollection("provider_profiles"))
                .thenThrow(new RuntimeException("not authorized"));

        assertThatThrownBy(() ->
                initializer.ensureUniqueIndex(
                        "provider_profiles",
                        List.of("userId"),
                        "userId_1"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Startup failed");
    }

    @Test
    @DisplayName("Fails safely when userId index exists but is not unique")
    void failsWhenSameKeyIsNotUnique() {
        Document notUnique = new Document("v", 2)
                .append(
                        "key",
                        new Document("userId", 1)
                )
                .append("name", "userId_1");

        when(mongoTemplate.getCollection("provider_profiles"))
                .thenReturn(providerProfilesCollection);

        ListIndexesIterable<Document> existing =
                indexes(ID_INDEX, notUnique);

        when(providerProfilesCollection.listIndexes())
                .thenReturn(existing);

        assertThatThrownBy(() ->
                initializer.ensureUniqueIndex(
                        "provider_profiles",
                        List.of("userId"),
                        "userId_1"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index")
                .hasMessageContaining("Manual migration required");

        verify(providerProfilesCollection, never())
                .createIndex(
                        any(Bson.class),
                        any(IndexOptions.class)
                );

        verify(providerProfilesCollection, never())
                .dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails when index name belongs to a different key")
    void failsWhenNameUsedByDifferentKey() {
        Document wrongKey = new Document("v", 2)
                .append(
                        "key",
                        new Document("email", 1)
                )
                .append("name", "userId_1")
                .append("unique", true);

        when(mongoTemplate.getCollection("provider_profiles"))
                .thenReturn(providerProfilesCollection);

        ListIndexesIterable<Document> existing =
                indexes(ID_INDEX, wrongKey);

        when(providerProfilesCollection.listIndexes())
                .thenReturn(existing);

        assertThatThrownBy(() ->
                initializer.ensureUniqueIndex(
                        "provider_profiles",
                        List.of("userId"),
                        "userId_1"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index");

        verify(providerProfilesCollection, never())
                .createIndex(
                        any(Bson.class),
                        any(IndexOptions.class)
                );

        verify(providerProfilesCollection, never())
                .dropIndex(anyString());
    }

    @Test
    @DisplayName("Fails when existing unique userId index is partial")
    void failsWhenIndexIsPartial() {
        Document partial = new Document("v", 2)
                .append(
                        "key",
                        new Document("userId", 1)
                )
                .append("name", "userId_1")
                .append("unique", true)
                .append(
                        "partialFilterExpression",
                        new Document(
                                "userId",
                                new Document("$type", "string")
                        )
                );

        when(mongoTemplate.getCollection("provider_profiles"))
                .thenReturn(providerProfilesCollection);

        ListIndexesIterable<Document> existing =
                indexes(ID_INDEX, partial);

        when(providerProfilesCollection.listIndexes())
                .thenReturn(existing);

        assertThatThrownBy(() ->
                initializer.ensureUniqueIndex(
                        "provider_profiles",
                        List.of("userId"),
                        "userId_1"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incompatible index");
    }

    @Test
    @DisplayName("Fails when userId index is missing after creation")
    void failsWhenIndexMissingAfterCreation() {
        when(mongoTemplate.getCollection("provider_profiles"))
                .thenReturn(providerProfilesCollection);

        ListIndexesIterable<Document> before = indexes(ID_INDEX);
        ListIndexesIterable<Document> after = indexes(ID_INDEX);

        when(providerProfilesCollection.listIndexes())
                .thenReturn(before, after);

        assertThatThrownBy(() ->
                initializer.ensureUniqueIndex(
                        "provider_profiles",
                        List.of("userId"),
                        "userId_1"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "was not found after creation"
                );

        verify(providerProfilesCollection, times(1))
                .createIndex(
                        any(Bson.class),
                        any(IndexOptions.class)
                );
    }

    @Test
    @DisplayName("Wraps duplicate-data MongoDB error as startup failure")
    void wrapsMongoErrorOnCreate() {
        when(mongoTemplate.getCollection("provider_profiles"))
                .thenReturn(providerProfilesCollection);

        ListIndexesIterable<Document> before =
                indexes(ID_INDEX);

        when(providerProfilesCollection.listIndexes())
                .thenReturn(before);

        when(providerProfilesCollection.createIndex(
                any(Bson.class),
                any(IndexOptions.class)
        ))
                .thenThrow(
                        new RuntimeException(
                                "E11000 duplicate key error"
                        )
                );

        assertThatThrownBy(() ->
                initializer.ensureUniqueIndex(
                        "provider_profiles",
                        List.of("userId"),
                        "userId_1"
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Startup failed");
    }
}
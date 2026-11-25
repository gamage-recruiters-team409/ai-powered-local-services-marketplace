package com.gamagerecruiters.marketplace.modules.notification.initializer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;

@ExtendWith(MockitoExtension.class)
class NotificationIndexInitializerTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private MongoCollection<Document> notifications;

    private NotificationIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new NotificationIndexInitializer(mongoTemplate);
    }

    @SuppressWarnings("unchecked")
    private static ListIndexesIterable<Document> indexes(Document... documents) {
        ListIndexesIterable<Document> result = mock(ListIndexesIterable.class);
        when(result.into(any())).thenAnswer(invocation -> {
            Collection<Document> target = invocation.getArgument(0);
            target.addAll(List.of(documents));
            return target;
        });
        return result;
    }

    @Test
    void createsBothIndexesOnFreshDatabase() {
        Document history = new Document("key", new Document("userId", 1)
                .append("createdAt", -1)
                .append("_id", -1))
                .append("name", NotificationIndexInitializer.HISTORY_INDEX);
        Document unread = new Document("key", new Document("userId", 1)
                .append("readAt", 1))
                .append("name", NotificationIndexInitializer.UNREAD_INDEX);

        ListIndexesIterable<Document> emptyIndexes = indexes();
        ListIndexesIterable<Document> historyOnlyAfterCreate = indexes(history);
        ListIndexesIterable<Document> historyOnlyBeforeUnreadCreate = indexes(history);
        ListIndexesIterable<Document> bothIndexesAfterCreate = indexes(history, unread);

        when(mongoTemplate.collectionExists(NotificationIndexInitializer.COLLECTION))
                .thenReturn(false, true);
        when(mongoTemplate.getCollection(NotificationIndexInitializer.COLLECTION))
                .thenReturn(notifications);
        when(notifications.listIndexes()).thenReturn(
                emptyIndexes,
                historyOnlyAfterCreate,
                historyOnlyBeforeUnreadCreate,
                bothIndexesAfterCreate);

        assertThatCode(initializer::initializeIndexes).doesNotThrowAnyException();

        verify(notifications, times(2)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    void neverDropsExistingIndexes() {
        Document history = new Document("key", new Document("userId", 1)
                .append("createdAt", -1)
                .append("_id", -1))
                .append("name", NotificationIndexInitializer.HISTORY_INDEX);
        Document unread = new Document("key", new Document("userId", 1)
                .append("readAt", 1))
                .append("name", NotificationIndexInitializer.UNREAD_INDEX);

        ListIndexesIterable<Document> bothIndexes = indexes(history, unread);

        when(mongoTemplate.collectionExists(anyString())).thenReturn(true);
        when(mongoTemplate.getCollection(anyString())).thenReturn(notifications);
        when(notifications.listIndexes()).thenReturn(bothIndexes);

        assertThatCode(initializer::initializeIndexes).doesNotThrowAnyException();

        verify(notifications, never()).dropIndex(anyString());
    }

    @Test
    void incompatibleExistingIndexThrowsInsteadOfDroppingIt() {
        Document incompatible = new Document("key", new Document("userId", 1)
                .append("createdAt", 1)
                .append("_id", -1))
                .append("name", NotificationIndexInitializer.HISTORY_INDEX);

        ListIndexesIterable<Document> incompatibleOnly = indexes(incompatible);

        when(mongoTemplate.collectionExists(anyString())).thenReturn(true);
        when(mongoTemplate.getCollection(anyString())).thenReturn(notifications);
        when(notifications.listIndexes()).thenReturn(incompatibleOnly);

        assertThatThrownBy(initializer::initializeIndexes)
                .isInstanceOf(IllegalStateException.class);

        verify(notifications, never()).dropIndex(anyString());
    }
}

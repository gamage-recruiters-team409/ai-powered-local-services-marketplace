package com.gamagerecruiters.marketplace.modules.messaging.initializer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;

@ExtendWith(MockitoExtension.class)
class MessagingIndexInitializerTest {

    @Mock private MongoTemplate mongoTemplate;
    @Mock private MongoCollection<Document> conversations;
    @Mock private MongoCollection<Document> messages;
    private MessagingIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new MessagingIndexInitializer(mongoTemplate);
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
    void createsCollectionsAndIndexesOnFreshDatabase() {
        when(mongoTemplate.collectionExists("conversations")).thenReturn(false);
        when(mongoTemplate.collectionExists("messages")).thenReturn(false);
        when(mongoTemplate.getCollection("conversations")).thenReturn(conversations);
        when(mongoTemplate.getCollection("messages")).thenReturn(messages);
        ListIndexesIterable<Document> conversationBefore = indexes();
        ListIndexesIterable<Document> conversationAfter = indexes(new Document("key",
                new Document("chatKey", 1)).append("name", "chatKey_1").append("unique", true));
        ListIndexesIterable<Document> messageBefore = indexes();
        ListIndexesIterable<Document> messageAfter = indexes(new Document("key",
                new Document("conversationId", 1).append("createdAt", 1))
                .append("name", "conversationId_1_createdAt_1"));
        when(conversations.listIndexes()).thenReturn(conversationBefore, conversationAfter);
        when(messages.listIndexes()).thenReturn(messageBefore, messageAfter);

        assertThatCode(initializer::initializeIndexes).doesNotThrowAnyException();
        verify(conversations).createIndex(any(Bson.class), any(IndexOptions.class));
        verify(messages).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    void doesNotDropExistingIndexes() {
        Document chat = new Document("key", new Document("chatKey", 1))
                .append("name", "chatKey_1").append("unique", true);
        Document message = new Document("key", new Document("conversationId", 1).append("createdAt", 1))
                .append("name", "conversationId_1_createdAt_1");
        when(mongoTemplate.collectionExists(anyString())).thenReturn(true);
        when(mongoTemplate.getCollection("conversations")).thenReturn(conversations);
        when(mongoTemplate.getCollection("messages")).thenReturn(messages);
        ListIndexesIterable<Document> conversationIndexes = indexes(chat);
        ListIndexesIterable<Document> messageIndexes = indexes(message);
        when(conversations.listIndexes()).thenReturn(conversationIndexes);
        when(messages.listIndexes()).thenReturn(messageIndexes);

        assertThatCode(initializer::initializeIndexes).doesNotThrowAnyException();
        verify(conversations, never()).dropIndex(anyString());
        verify(messages, never()).dropIndex(anyString());
    }
}

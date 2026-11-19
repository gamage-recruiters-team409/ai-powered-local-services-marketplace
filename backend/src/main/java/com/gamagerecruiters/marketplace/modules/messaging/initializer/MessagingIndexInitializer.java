package com.gamagerecruiters.marketplace.modules.messaging.initializer;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

@Component
@Profile("!test")
public class MessagingIndexInitializer {

    static final String CONVERSATIONS = "conversations";
    static final String MESSAGES = "messages";
    static final String CHAT_KEY_INDEX = "chatKey_1";
    static final String CONVERSATION_CREATED_INDEX = "conversationId_1_createdAt_1";
    private static final Logger log = LoggerFactory.getLogger(MessagingIndexInitializer.class);
    private final MongoTemplate mongoTemplate;

    public MessagingIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        ensureCollectionExists(CONVERSATIONS);
        ensureCollectionExists(MESSAGES);
        ensureIndex(CONVERSATIONS, List.of("chatKey"), CHAT_KEY_INDEX, true);
        ensureIndex(MESSAGES, List.of("conversationId", "createdAt"), CONVERSATION_CREATED_INDEX, false);
    }

    void ensureIndex(String collectionName, List<String> fields, String indexName, boolean unique) {
        try {
            MongoCollection<Document> collection = mongoTemplate.getCollection(collectionName);
            List<Document> indexes = collection.listIndexes().into(new ArrayList<>());
            Document existing = findByKey(indexes, fields);
            if (existing == null) {
                Document sameName = findByName(indexes, indexName);
                if (sameName != null) {
                    throw incompatible(collectionName, fields, unique, sameName);
                }
                collection.createIndex(Indexes.ascending(fields), new IndexOptions().unique(unique).name(indexName));
                indexes = collection.listIndexes().into(new ArrayList<>());
                existing = findByKey(indexes, fields);
                if (existing == null) {
                    throw new IllegalStateException("Startup failed: index '" + indexName + "' on "
                            + collectionName + " was not found after creation.");
                }
            }
            if (isUniqueTrue(existing) != unique
                    || existing.containsKey("partialFilterExpression") || isSparseTrue(existing)) {
                throw incompatible(collectionName, fields, unique, existing);
            }
            log.info("Index verified: {} -> {}", collectionName, existing.toJson());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Startup failed: Unable to create or verify required index '"
                    + indexName + "' on " + collectionName + " collection.", e);
        }
    }

    void ensureCollectionExists(String collectionName) {
        try {
            if (mongoTemplate.collectionExists(collectionName)) return;
            try {
                mongoTemplate.createCollection(collectionName);
            } catch (RuntimeException e) {
                if (!mongoTemplate.collectionExists(collectionName)) throw e;
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Startup failed: Unable to create required collection '"
                    + collectionName + "'.", e);
        }
    }

    private Document findByKey(List<Document> indexes, List<String> fields) {
        for (Document index : indexes) {
            Document key = index.get("key", Document.class);
            if (key != null && hasExactAscendingKey(key, fields)) return index;
        }
        return null;
    }

    private Document findByName(List<Document> indexes, String name) {
        for (Document index : indexes) if (name.equals(index.getString("name"))) return index;
        return null;
    }

    private boolean hasExactAscendingKey(Document key, List<String> fields) {
        if (!new ArrayList<>(key.keySet()).equals(fields)) return false;
        for (String field : fields) {
            if (!(key.get(field) instanceof Number direction) || direction.intValue() != 1) return false;
        }
        return true;
    }

    private boolean isUniqueTrue(Document index) {
        Object value = index.get("unique");
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() == 1;
    }

    private boolean isSparseTrue(Document index) {
        Object value = index.get("sparse");
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() == 1;
    }

    private IllegalStateException incompatible(String collectionName, List<String> fields, boolean unique,
                                               Document found) {
        return new IllegalStateException("Incompatible index '" + found.getString("name") + "' detected on "
                + collectionName + ". Expected " + (unique ? "a unique" : "a non-unique")
                + " ascending index on " + fields + " (no partial filter, not sparse), but found "
                + found.toJson() + ". Automatic drop is disabled for safety. Manual migration required.");
    }
}

package com.gamagerecruiters.marketplace.modules.notification.initializer;

import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.conversions.Bson;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class NotificationIndexInitializer {
    static final String COLLECTION = "notifications";
    static final String HISTORY_INDEX = "userId_1_createdAt_-1__id_-1";
    static final String UNREAD_INDEX = "userId_1_readAt_1";
    private final MongoTemplate mongoTemplate;
    public NotificationIndexInitializer(MongoTemplate mongoTemplate) { this.mongoTemplate = mongoTemplate; }
    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() { ensureHistoryIndex(); ensureUnreadIndex(); }
    void ensureHistoryIndex() { ensure(HISTORY_INDEX, new Document("userId", 1).append("createdAt", -1).append("_id", -1)); }
    void ensureUnreadIndex() { ensure(UNREAD_INDEX, new Document("userId", 1).append("readAt", 1)); }
    private void ensure(String name, Document expected) {
        try {
            ensureCollection(); MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION);
            List<Document> indexes = collection.listIndexes().into(new ArrayList<>()); Document existing = byName(indexes, name);
            if (existing == null) { Document conflict = byKey(indexes, expected); if (conflict != null) throw incompatible(name, conflict);
                List<Bson> fields = expected.entrySet().stream().map(e -> e.getValue().equals(1) ? Indexes.ascending(e.getKey()) : Indexes.descending(e.getKey())).toList();
                collection.createIndex(Indexes.compoundIndex(fields), new IndexOptions().name(name).unique(false));
                existing = byName(collection.listIndexes().into(new ArrayList<>()), name); }
            if (existing == null || !expected.equals(existing.get("key", Document.class)) || Boolean.TRUE.equals(existing.get("unique")) || existing.get("partialFilterExpression") != null) throw incompatible(name, existing);
        } catch (IllegalStateException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalStateException("Unable to create or verify notification index '" + name + "'.", ex); }
    }
    private void ensureCollection() { if (!mongoTemplate.collectionExists(COLLECTION)) { try { mongoTemplate.createCollection(COLLECTION); } catch (RuntimeException ex) { if (!mongoTemplate.collectionExists(COLLECTION)) throw ex; } } }
    private Document byName(List<Document> indexes, String name) { return indexes.stream().filter(index -> name.equals(index.getString("name"))).findFirst().orElse(null); }
    private Document byKey(List<Document> indexes, Document key) { return indexes.stream().filter(index -> key.equals(index.get("key", Document.class))).findFirst().orElse(null); }
    private IllegalStateException incompatible(String name, Document found) { return new IllegalStateException("Incompatible notification index '" + name + "' detected: " + (found == null ? "missing" : found.toJson()) + ". Automatic index deletion is disabled; manual database migration is required."); }
}

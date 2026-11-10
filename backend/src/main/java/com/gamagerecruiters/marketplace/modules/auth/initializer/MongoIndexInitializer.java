package com.gamagerecruiters.marketplace.modules.auth.initializer;

import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.mongodb.client.MongoCollection;

@Component
@Profile("!test")
public class MongoIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public MongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for auth collections...");

        initUsersEmailIndex();
        initUsersPhonePartialIndex();
        initAuthTokensTokenHashIndex();
        initAuthTokensTtlIndex();

        log.info("All required MongoDB indexes for auth module verified successfully.");
    }

    private void initUsersEmailIndex() {
        try {
            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(User.class).ensureIndex(
                    new Index().on("email", Sort.Direction.ASC).unique().named("email_1")
            );
            log.info("Index verified: users.email (unique) -> {}", result);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required unique index 'email_1' on users collection. " +
                    "Ensure no duplicate emails exist in the database.", e
            );
        }
    }

    private void initUsersPhonePartialIndex() {
        try {
            MongoCollection<Document> usersCollection = mongoTemplate.getCollection("users");
            Document expectedPhoneFilter = new Document("phone", new Document("$type", "string"));

            // Inspect existing indexes — never drop, only validate
            for (Document indexDoc : usersCollection.listIndexes()) {
                String indexName = indexDoc.getString("name");
                Document key = indexDoc.get("key", Document.class);

                if (key != null && key.containsKey("phone")) {
                    boolean validKey = key.size() == 1 && isDirectionAscending(key.get("phone"));
                    boolean validUnique = isUniqueTrue(indexDoc);
                    Document partialFilter = indexDoc.get("partialFilterExpression", Document.class);
                    boolean validFilter = expectedPhoneFilter.equals(partialFilter);

                    if (validKey && validUnique && validFilter) {
                        log.info("Index verified: users.phone (partial unique, string type) -> existing '{}' matches expected specification.", indexName);
                        return;
                    }

                    // Index on phone exists but does not strictly match required specification
                    throw new IllegalStateException(
                            "Incompatible index '" + indexName + "' detected on users.phone. " +
                            "Expected key {phone: 1}, unique: true, and partialFilterExpression: {phone: {$type: 'string'}}, " +
                            "but found [key=" + key.toJson() + ", unique=" + indexDoc.get("unique") +
                            ", partialFilterExpression=" + (partialFilter != null ? partialFilter.toJson() : "null") + "]. " +
                            "Automatic drop is disabled for safety. Manual migration required: " +
                            "see docs/db-migrations/2026-11-users-phone-partial-index.md " +
                            "and run docs/db-migrations/users-phone-partial-index.js via mongosh."
                    );
                }
            }

            // No phone index found — create the partial unique index
            Document filterDoc = new Document("phone", new Document("$type", "string"));
            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(User.class).ensureIndex(
                    new Index()
                            .on("phone", Sort.Direction.ASC)
                            .unique()
                            .partial(PartialIndexFilter.of(filterDoc))
                            .named("phone_1")
            );
            log.info("Index created: users.phone (partial unique, string type) -> {}", result);
        } catch (IllegalStateException e) {
            throw e; // Re-throw our own validation errors
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required partial unique index 'phone_1' on users collection. " +
                    "Ensure no duplicate phone numbers exist among string-typed phone records.", e
            );
        }
    }

    private boolean isDirectionAscending(Object dir) {
        if (dir instanceof Number n) {
            return n.intValue() == 1;
        }
        return false;
    }

    private boolean isUniqueTrue(Document indexDoc) {
        Object val = indexDoc.get("unique");
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof Number n) {
            return n.intValue() == 1;
        }
        return false;
    }

    private void initAuthTokensTokenHashIndex() {
        try {
            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(AuthToken.class).ensureIndex(
                    new Index().on("tokenHash", Sort.Direction.ASC).unique().named("tokenHash_1")
            );
            log.info("Index verified: auth_tokens.tokenHash (unique) -> {}", result);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required unique index 'tokenHash_1' on auth_tokens collection.", e
            );
        }
    }

    private void initAuthTokensTtlIndex() {
        try {
            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(AuthToken.class).ensureIndex(
                    new Index().on("expiresAt", Sort.Direction.ASC).expire(0, TimeUnit.SECONDS).named("expiresAt_1")
            );
            log.info("Index verified: auth_tokens.expiresAt (TTL) -> {}", result);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required TTL index 'expiresAt_1' on auth_tokens collection.", e
            );
        }
    }
}

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
    @SuppressWarnings("removal")
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for auth collections...");

        initUsersEmailIndex();
        initUsersPhonePartialIndex();
        initAuthTokensTokenHashIndex();
        initAuthTokensTtlIndex();

        log.info("All required MongoDB indexes for auth module verified successfully.");
    }

    @SuppressWarnings("removal")
    private void initUsersEmailIndex() {
        try {
            mongoTemplate.indexOps(User.class).ensureIndex(
                    new Index().on("email", Sort.Direction.ASC).unique().named("email_1")
            );
            log.info("Index verified: users.email (unique)");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required unique index 'email_1' on users collection. " +
                    "Ensure no duplicate emails exist in the database.", e
            );
        }
    }

    @SuppressWarnings("removal")
    private void initUsersPhonePartialIndex() {
        try {
            MongoCollection<Document> usersCollection = mongoTemplate.getCollection("users");
            // Check for legacy non-partial index on phone
            for (Document indexDoc : usersCollection.listIndexes()) {
                String indexName = indexDoc.getString("name");
                Document key = indexDoc.get("key", Document.class);
                if ("phone_1".equals(indexName) || (key != null && key.containsKey("phone") && key.size() == 1)) {
                    Document partialFilter = indexDoc.get("partialFilterExpression", Document.class);
                    if (partialFilter == null) {
                        log.info("Found legacy non-partial index '{}' on users.phone. Dropping to apply partial unique filter...", indexName);
                        usersCollection.dropIndex(indexName);
                    }
                }
            }

            // Create partial unique index: unique only when phone is a string (leaves absent/null phones unindexed)
            Document filterDoc = new Document("phone", new Document("$type", "string"));
            mongoTemplate.indexOps(User.class).ensureIndex(
                    new Index()
                            .on("phone", Sort.Direction.ASC)
                            .unique()
                            .partial(PartialIndexFilter.of(filterDoc))
                            .named("phone_1")
            );
            log.info("Index verified: users.phone (partial unique, string type)");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required partial unique index 'phone_1' on users collection. " +
                    "Ensure no duplicate phone numbers exist among string-typed phone records.", e
            );
        }
    }

    @SuppressWarnings("removal")
    private void initAuthTokensTokenHashIndex() {
        try {
            mongoTemplate.indexOps(AuthToken.class).ensureIndex(
                    new Index().on("tokenHash", Sort.Direction.ASC).unique().named("tokenHash_1")
            );
            log.info("Index verified: auth_tokens.tokenHash (unique)");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required unique index 'tokenHash_1' on auth_tokens collection.", e
            );
        }
    }

    @SuppressWarnings("removal")
    private void initAuthTokensTtlIndex() {
        try {
            mongoTemplate.indexOps(AuthToken.class).ensureIndex(
                    new Index().on("expiresAt", Sort.Direction.ASC).expire(0, TimeUnit.SECONDS).named("expiresAt_1")
            );
            log.info("Index verified: auth_tokens.expiresAt (TTL)");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required TTL index 'expiresAt_1' on auth_tokens collection.", e
            );
        }
    }
}

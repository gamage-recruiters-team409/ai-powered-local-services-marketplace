package com.gamagerecruiters.marketplace.modules.auth.initializer;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;

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
        try {
            // Index on users: unique email and unique phone
            mongoTemplate.indexOps(User.class).ensureIndex(
                    new Index().on("email", Sort.Direction.ASC).unique()
            );
            mongoTemplate.indexOps(User.class).ensureIndex(
                    new Index().on("phone", Sort.Direction.ASC).unique()
            );

            // Index on auth_tokens: unique tokenHash and TTL index on expiresAt
            mongoTemplate.indexOps(AuthToken.class).ensureIndex(
                    new Index().on("tokenHash", Sort.Direction.ASC).unique()
            );
            mongoTemplate.indexOps(AuthToken.class).ensureIndex(
                    new Index().on("expiresAt", Sort.Direction.ASC).expire(0, TimeUnit.SECONDS)
            );

            log.info("MongoDB auth indexes verified successfully.");
        } catch (Exception e) {
            log.warn("Could not ensure MongoDB auth indexes at startup: {}", e.getMessage());
        }
    }
}

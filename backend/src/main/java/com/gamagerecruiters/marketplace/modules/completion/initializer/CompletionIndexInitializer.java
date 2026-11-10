package com.gamagerecruiters.marketplace.modules.completion.initializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;

@Component
@Profile("!test")
public class CompletionIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(CompletionIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public CompletionIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        try {
            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(ServiceCompletion.class).ensureIndex(
                    new Index().on("bookingId", Sort.Direction.ASC).unique().named("bookingId_1")
            );
            log.info("Index verified: service_completions.bookingId (unique) -> {}", result);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required unique index 'bookingId_1' on service_completions. " +
                    "Ensure no booking has more than one completion record.", e
            );
        }
    }
}

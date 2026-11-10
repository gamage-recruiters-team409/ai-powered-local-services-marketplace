package com.gamagerecruiters.marketplace.modules.availability.initializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.availability.domain.AvailabilityRule;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockedDate;

@Component
@Profile("!test")
public class AvailabilityIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public AvailabilityIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        log.info("Initializing and validating MongoDB indexes for availability collections...");

        initAvailabilityRulesProviderIndex();
        initBlockedDatesProviderDateIndex();

        log.info("All required MongoDB indexes for availability module verified successfully.");
    }

    private void initAvailabilityRulesProviderIndex() {
        try {
            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(AvailabilityRule.class).ensureIndex(
                    new Index().on("providerId", Sort.Direction.ASC).unique().named("providerId_1")
            );
            log.info("Index verified: availability_rules.providerId (unique) -> {}", result);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required unique index 'providerId_1' on availability_rules collection.", e
            );
        }
    }

    private void initBlockedDatesProviderDateIndex() {
        try {
            @SuppressWarnings("removal")
            String result = mongoTemplate.indexOps(BlockedDate.class).ensureIndex(
                    new Index()
                            .on("providerId", Sort.Direction.ASC)
                            .on("date", Sort.Direction.ASC)
                            .unique()
                            .named("providerId_1_date_1")
            );
            log.info("Index verified: blocked_dates.providerId+date (unique) -> {}", result);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Startup failed: Unable to create required unique index 'providerId_1_date_1' on blocked_dates collection.", e
            );
        }
    }
}

package com.gamagerecruiters.marketplace.modules.operations.initializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseAction;

/**
 * Non-destructive index initializer for {@code ops_case_actions} (WP09). Ensures the two query indexes used to find a
 * case's latest action and to list cases by current status:
 * <ul>
 *   <li>{@code (caseType ASC, subjectId ASC, createdAt DESC)} — the latest action for a case.</li>
 *   <li>{@code (caseType ASC, caseStatus ASC, createdAt DESC)} — cases by status, newest first.</li>
 * </ul>
 */
@Component
@Profile("!test")
public class OpsCaseActionIndexInitializer {

    static final String CASE_SUBJECT_CREATED_INDEX = "caseType_1_subjectId_1_createdAt_-1";
    static final String CASE_STATUS_CREATED_INDEX = "caseType_1_caseStatus_1_createdAt_-1";

    private static final Logger log = LoggerFactory.getLogger(OpsCaseActionIndexInitializer.class);

    private final MongoTemplate mongoTemplate;

    public OpsCaseActionIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndexes() {
        if (!mongoTemplate.collectionExists(OpsCaseAction.COLLECTION)) {
            mongoTemplate.createCollection(OpsCaseAction.COLLECTION);
            log.info("Created MongoDB collection '{}'.", OpsCaseAction.COLLECTION);
        }
        ensure(new Index()
                .on("caseType", Sort.Direction.ASC)
                .on("subjectId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC)
                .named(CASE_SUBJECT_CREATED_INDEX));
        ensure(new Index()
                .on("caseType", Sort.Direction.ASC)
                .on("caseStatus", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC)
                .named(CASE_STATUS_CREATED_INDEX));
        log.info("ops_case_actions indexes verified.");
    }

    @SuppressWarnings("removal")
    private void ensure(Index index) {
        mongoTemplate.indexOps(OpsCaseAction.class).ensureIndex(index);
    }
}

package com.gamagerecruiters.marketplace.modules.operations.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.operations.domain.OperationsRules;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActionType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActorArea;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseAction;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;
import com.gamagerecruiters.marketplace.modules.operations.service.OpsCaseActionStore;

/**
 * The operational action log against a REAL MongoDB in a throw-away Testcontainers instance: latest-status resolution,
 * resolved-today counting by the Asia/Colombo day, and consistent latest status under concurrent actions on one case.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class OpsCaseActionStoreMongoIntegrationTest {

    private static final String TEST_DATABASE = "operations_it";
    private static final String FORBIDDEN_DATABASE = "marketplace";

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl(TEST_DATABASE);
        });
    }

    @BeforeAll
    static void startContainer() {
        if (!mongoDBContainer.isRunning()) {
            mongoDBContainer.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    private OpsCaseActionStore store;

    @BeforeEach
    void setUp() {
        String dbName = mongoTemplate.getDb().getName();
        if (FORBIDDEN_DATABASE.equals(dbName)) {
            throw new IllegalStateException("Refusing to run against the shared database \"" + FORBIDDEN_DATABASE + "\"");
        }
        assertThat(dbName).isEqualTo(TEST_DATABASE);
        mongoTemplate.remove(new Query(), OpsCaseAction.class);
        store = new OpsCaseActionStore(mongoTemplate);
    }

    private OpsCaseAction action(String subject, OpsActionType type, OpsCaseStatus status, Instant at) {
        return new OpsCaseAction(OpsCaseType.OVERDUE_BOOKING, subject, type, "A note long enough to be valid.",
                "admin-1", OpsActorArea.SUPPORT_ADMIN, at, status);
    }

    @Test
    @DisplayName("current status of a case is the latest recorded action; default OPEN when none")
    void latestStatus() {
        Instant base = Instant.parse("2026-11-26T04:00:00Z");
        store.insert(action("b1", OpsActionType.CONTACT_PROVIDER, OpsCaseStatus.CONTACTED, base));
        store.insert(action("b1", OpsActionType.ESCALATE, OpsCaseStatus.ESCALATED, base.plusSeconds(60)));

        assertThat(store.latest(OpsCaseType.OVERDUE_BOOKING, "b1"))
                .get().extracting(OpsCaseAction::getCaseStatus).isEqualTo(OpsCaseStatus.ESCALATED);
        assertThat(store.latest(OpsCaseType.OVERDUE_BOOKING, "never-touched")).isEmpty();
    }

    @Test
    @DisplayName("resolvedToday counts distinct cases resolved on the Asia/Colombo day")
    void resolvedTodayByColomboDay() {
        Instant now = Instant.parse("2026-11-26T04:00:00Z"); // 09:30 Asia/Colombo on 26 Nov
        store.insert(action("b1", OpsActionType.MARK_RESOLVED, OpsCaseStatus.RESOLVED, now));
        store.insert(action("b2", OpsActionType.MARK_RESOLVED, OpsCaseStatus.RESOLVED, now.plusSeconds(120)));
        // Yesterday (Asia/Colombo) — must NOT count.
        store.insert(action("b3", OpsActionType.MARK_RESOLVED, OpsCaseStatus.RESOLVED, now.minus(Duration.ofDays(1))));

        Instant from = now.atZone(OperationsRules.ZONE).toLocalDate().atStartOfDay(OperationsRules.ZONE).toInstant();
        Instant to = now.atZone(OperationsRules.ZONE).toLocalDate().plusDays(1)
                .atStartOfDay(OperationsRules.ZONE).toInstant();

        assertThat(store.countResolvedToday(OpsCaseType.OVERDUE_BOOKING, from, to)).isEqualTo(2);
    }

    @Test
    @DisplayName("concurrent actions on one case keep a single consistent latest status")
    void concurrentActionsConsistentLatest() throws Exception {
        Instant base = Instant.parse("2026-11-26T04:00:00Z");
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> store.insert(action("b1",
                        OpsActionType.CONTACT_PROVIDER, OpsCaseStatus.CONTACTED, base.plusSeconds(idx)))));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        // All actions persisted, and the single latest is the one with the greatest timestamp.
        assertThat(mongoTemplate.count(new Query(), OpsCaseAction.class)).isEqualTo(n);
        assertThat(store.latest(OpsCaseType.OVERDUE_BOOKING, "b1"))
                .get().extracting(OpsCaseAction::getCreatedAt).isEqualTo(base.plusSeconds(n - 1));
    }
}

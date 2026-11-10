package com.gamagerecruiters.marketplace.modules.availability.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.availability.domain.AvailabilityRule;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockedDate;
import com.gamagerecruiters.marketplace.modules.availability.dto.AvailabilityResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.UpdateAvailabilityRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursRequest;
import com.gamagerecruiters.marketplace.modules.availability.initializer.AvailabilityIndexInitializer;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityService;

/**
 * Runs the availability module against a real MongoDB (Testcontainers) to verify:
 * index creation on a completely fresh database, the real unique index definitions,
 * and simultaneous first-time schedule updates.
 * Requires Docker. Skip explicitly with -DSKIP_DOCKER_TESTS=true.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class AvailabilityMongoIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityMongoIntegrationTest.class);

    private static final int PARALLEL_REQUESTS = 10;

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl();
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
    private AvailabilityService availabilityService;

    @Autowired
    private AvailabilityIndexInitializer indexInitializer;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanData() {
        // Remove documents only; indexes stay in place.
        mongoTemplate.remove(new Query(), AvailabilityRule.class);
        mongoTemplate.remove(new Query(), BlockedDate.class);
    }

    @Test
    @DisplayName("Real MongoDB: required unique indexes exist with the expected keys, order and unique flag")
    void requiredIndexesExistInRealMongo() {
        // Startup already ran the initializer. Running it again must verify the existing indexes without errors.
        indexInitializer.initializeIndexes();

        Document rulesIndex = findIndex("availability_rules", "providerId_1");
        Document blockedIndex = findIndex("blocked_dates", "providerId_1_date_1");

        log.info("availability_rules index definition: {}", rulesIndex.toJson());
        log.info("blocked_dates index definition: {}", blockedIndex.toJson());

        assertThat(new ArrayList<>(rulesIndex.get("key", Document.class).keySet())).containsExactly("providerId");
        assertThat(rulesIndex.getBoolean("unique")).isTrue();

        assertThat(new ArrayList<>(blockedIndex.get("key", Document.class).keySet()))
                .containsExactly("providerId", "date");
        assertThat(blockedIndex.getBoolean("unique")).isTrue();
    }

    @Test
    @DisplayName("Real MongoDB, fresh database: collections and unique indexes are created from nothing")
    void freshDatabaseCreatesCollectionsAndIndexes() {
        // Make this test container database completely fresh for the availability module.
        mongoTemplate.dropCollection(AvailabilityRule.class);
        mongoTemplate.dropCollection(BlockedDate.class);
        assertThat(mongoTemplate.collectionExists("availability_rules")).isFalse();
        assertThat(mongoTemplate.collectionExists("blocked_dates")).isFalse();

        indexInitializer.initializeIndexes();

        assertThat(mongoTemplate.collectionExists("availability_rules")).isTrue();
        assertThat(mongoTemplate.collectionExists("blocked_dates")).isTrue();

        Document rulesIndex = findIndex("availability_rules", "providerId_1");
        Document blockedIndex = findIndex("blocked_dates", "providerId_1_date_1");
        log.info("Fresh database - availability_rules index created: {}", rulesIndex.toJson());
        log.info("Fresh database - blocked_dates index created: {}", blockedIndex.toJson());

        assertThat(new ArrayList<>(rulesIndex.get("key", Document.class).keySet())).containsExactly("providerId");
        assertThat(rulesIndex.getBoolean("unique")).isTrue();
        assertThat(new ArrayList<>(blockedIndex.get("key", Document.class).keySet()))
                .containsExactly("providerId", "date");
        assertThat(blockedIndex.getBoolean("unique")).isTrue();
    }

    @Test
    @DisplayName("Real MongoDB: 10 simultaneous first-time updates for one provider all succeed and create one schedule")
    void simultaneousFirstTimeUpdatesCreateOneSchedule() throws Exception {
        String providerId = "concurrent-provider-1";
        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS);
        CountDownLatch ready = new CountDownLatch(PARALLEL_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Future<AvailabilityResponse>> results = new ArrayList<>();

        try {
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
                int bufferMinutes = i * 10;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return availabilityService.updateAvailability(providerId,
                                new UpdateAvailabilityRequest(fullWeek(), bufferMinutes));
                    } catch (RuntimeException e) {
                        errors.add(e);
                        throw e;
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<AvailabilityResponse> result : results) {
                try {
                    assertThat(result.get(30, TimeUnit.SECONDS).providerId()).isEqualTo(providerId);
                } catch (java.util.concurrent.ExecutionException e) {
                    // recorded in errors
                }
            }
        } finally {
            executor.shutdownNow();
        }

        long schedules = mongoTemplate.count(
                Query.query(Criteria.where("providerId").is(providerId)), AvailabilityRule.class);
        AvailabilityRule saved = mongoTemplate.findOne(
                Query.query(Criteria.where("providerId").is(providerId)), AvailabilityRule.class);

        log.info("Concurrent first-time updates: requests={}, errors={}, schedules stored={}",
                PARALLEL_REQUESTS, errors.size(), schedules);

        assertThat(errors).as("no request should fail with an unhandled error").isEmpty();
        assertThat(schedules).as("one schedule per provider").isEqualTo(1);
        assertThat(saved).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getWeeklyHours()).hasSize(7);
        assertThat(saved.getBufferMinutes() % 10).isZero();
    }

    private Document findIndex(String collectionName, String indexName) {
        for (Document index : mongoTemplate.getCollection(collectionName).listIndexes()) {
            if (indexName.equals(index.getString("name"))) {
                return index;
            }
        }
        throw new AssertionError("Index " + indexName + " not found on " + collectionName);
    }

    private static List<WeeklyHoursRequest> fullWeek() {
        List<WeeklyHoursRequest> days = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            days.add(new WeeklyHoursRequest(day, true, "09:00", "17:00"));
        }
        return days;
    }
}

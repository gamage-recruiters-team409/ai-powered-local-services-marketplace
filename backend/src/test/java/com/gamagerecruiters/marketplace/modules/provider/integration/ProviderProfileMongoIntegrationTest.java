package com.gamagerecruiters.marketplace.modules.provider.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;
import com.gamagerecruiters.marketplace.modules.provider.dto.CreateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.initializer.ProviderProfileIndexInitializer;
import com.gamagerecruiters.marketplace.modules.provider.service.ProviderProfileService;

@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class ProviderProfileMongoIntegrationTest {

    private static final int PARALLEL_REQUESTS = 10;

    private static final String USER_ID = "concurrent-provider-user";
    private static final String EMAIL = "concurrent-provider@test.com";

    static MongoDBContainer mongoDBContainer =
            new MongoDBContainer("mongo:7.0");

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
    private ProviderProfileService providerProfileService;

    @Autowired
    private ProviderProfileIndexInitializer indexInitializer;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanData() {
        mongoTemplate.remove(new Query(), ProviderProfile.class);

        // Make sure the required unique index exists before each test.
        indexInitializer.initializeIndexes();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Real MongoDB: provider_profiles has unique ascending userId index")
    void providerProfileUniqueIndexExistsInRealMongo() {
        Document index = findIndex(
                "provider_profiles",
                "userId_1"
        );

        Document key = index.get("key", Document.class);

        assertThat(key).isNotNull();
        assertThat(new ArrayList<>(key.keySet()))
                .containsExactly("userId");
        assertThat(((Number) key.get("userId")).intValue())
                .isEqualTo(1);
        assertThat(index.getBoolean("unique"))
                .isTrue();
    }

    @Test
    @DisplayName("Real MongoDB: simultaneous profile creation persists exactly one profile and returns conflicts for duplicates")
    void simultaneousProfileCreationPersistsExactlyOneProfile()
            throws Exception {

        ExecutorService executor =
                Executors.newFixedThreadPool(PARALLEL_REQUESTS);

        CountDownLatch ready =
                new CountDownLatch(PARALLEL_REQUESTS);

        CountDownLatch start =
                new CountDownLatch(1);

        AtomicInteger successfulRequests =
                new AtomicInteger();

        AtomicInteger conflictRequests =
                new AtomicInteger();

        List<Throwable> unexpectedErrors =
                Collections.synchronizedList(
                        new ArrayList<>()
                );

        List<Future<?>> futures =
                new ArrayList<>();

        try {
            for (int i = 0; i < PARALLEL_REQUESTS; i++) {
                futures.add(executor.submit(() -> {
                    setProviderSecurityContext();

                    ready.countDown();

                    try {
                        start.await();

                        providerProfileService.createMyProfile(
                                createProfileRequest()
                        );

                        successfulRequests.incrementAndGet();

                    } catch (ResourceConflictException ex) {
                        assertThat(ex.getStatus())
                                .isEqualTo(HttpStatus.CONFLICT);

                        assertThat(ex.getErrorCode())
                                .isEqualTo(
                                        "PROVIDER_PROFILE_ALREADY_EXISTS"
                                );

                        conflictRequests.incrementAndGet();

                    } catch (Throwable ex) {
                        unexpectedErrors.add(ex);

                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }

            assertThat(
                    ready.await(10, TimeUnit.SECONDS)
            ).isTrue();

            start.countDown();

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

        } finally {
            executor.shutdownNow();
        }

        long storedProfiles = mongoTemplate.count(
                Query.query(
                        Criteria.where("userId").is(USER_ID)
                ),
                ProviderProfile.class
        );

        assertThat(unexpectedErrors)
                .as("No concurrent request should fail with an unhandled error")
                .isEmpty();

        assertThat(successfulRequests.get())
                .as("Exactly one profile creation should succeed")
                .isEqualTo(1);

        assertThat(conflictRequests.get())
                .as("All remaining profile creation attempts should return conflict")
                .isEqualTo(PARALLEL_REQUESTS - 1);

        assertThat(storedProfiles)
                .as("Only one provider profile may exist for the user")
                .isEqualTo(1);
    }

    private void setProviderSecurityContext() {
        UserPrincipal principal = new UserPrincipal(
                USER_ID,
                EMAIL,
                Role.PROVIDER
        );

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);
    }

    private CreateProviderProfileRequest createProfileRequest() {
        CreateProviderProfileRequest request =
                new CreateProviderProfileRequest();

        request.setProviderType(ProviderType.INDIVIDUAL);
        request.setDisplayName("Concurrent Provider");
        request.setBio("Concurrent profile creation test.");
        request.setDistrict("Colombo");
        request.setCity("Colombo");
        request.setExperienceYears(2);
        request.setProfileImageUrl(
                "https://example.com/provider.jpg"
        );

        return request;
    }

    private Document findIndex(
            String collectionName,
            String indexName
    ) {
        for (Document index :
                mongoTemplate
                        .getCollection(collectionName)
                        .listIndexes()) {

            if (indexName.equals(index.getString("name"))) {
                return index;
            }
        }

        throw new AssertionError(
                "Index " + indexName
                        + " not found on "
                        + collectionName
        );
    }
}
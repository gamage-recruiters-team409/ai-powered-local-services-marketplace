package com.gamagerecruiters.marketplace.modules.customer.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.mongodb.client.result.UpdateResult;

@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
public class CustomerProfilePhotoConcurrencyMongoIntegrationTest {

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
    private MongoTemplate mongoTemplate;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanUsers() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Concurrent update race: conditional update on profileImagePublicId prevents stale overwrite")
    void conditionalUpdate_detectsConcurrentChange_andDoesNotOverwrite() {
        // Given: User exists with photo-A
        User user = new User();
        user.setId("cust-concurrent-1");
        user.setName("Nimal Fernando");
        user.setEmail("nimal@marketplace.local");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setProfileImagePublicId("photo-A");
        user.setProfileImageUrl("https://cdn.example.com/photo-A.jpg");
        user.setCreatedAt(Instant.now());
        userRepository.save(user);

        // Simulated concurrent request: another request changes photo to photo-B first
        Query concurrentQuery = Query.query(Criteria.where("_id").is("cust-concurrent-1"));
        Update concurrentUpdate = new Update()
                .set("profileImagePublicId", "photo-B")
                .set("profileImageUrl", "https://cdn.example.com/photo-B.jpg");
        UpdateResult concurrentResult = mongoTemplate.updateFirst(concurrentQuery, concurrentUpdate, User.class);
        assertEquals(1, concurrentResult.getMatchedCount());

        // When: Stale request attempts to update expecting old photo-A
        Query staleQuery = Query.query(
                Criteria.where("_id").is("cust-concurrent-1")
                        .and("role").is(Role.CUSTOMER)
                        .and("status").is(UserStatus.ACTIVE)
                        .and("profileImagePublicId").is("photo-A")
        );
        Update staleUpdate = new Update()
                .set("profileImagePublicId", "photo-C-stale")
                .set("profileImageUrl", "https://cdn.example.com/photo-C-stale.jpg");

        UpdateResult staleResult = mongoTemplate.updateFirst(staleQuery, staleUpdate, User.class);

        // Then: Stale update matches 0 documents (detected concurrent modification)
        assertEquals(0, staleResult.getMatchedCount());
        assertEquals(0, staleResult.getModifiedCount());

        // And: Database still holds photo-B
        User currentInDb = userRepository.findById("cust-concurrent-1").orElseThrow();
        assertEquals("photo-B", currentInDb.getProfileImagePublicId());
        assertEquals("https://cdn.example.com/photo-B.jpg", currentInDb.getProfileImageUrl());
    }

    @Test
    @DisplayName("Initial upload: conditional update on null profileImagePublicId matches and succeeds")
    void conditionalUpdate_initialUploadWithNullPublicId_succeeds() {
        // Given: User with no profile photo (profileImagePublicId is null)
        User user = new User();
        user.setId("cust-init-1");
        user.setName("Sunil Silva");
        user.setEmail("sunil@marketplace.local");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setProfileImagePublicId(null);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);

        // When: Upload with expectedOldPublicId == null
        Query initQuery = Query.query(
                Criteria.where("_id").is("cust-init-1")
                        .and("role").is(Role.CUSTOMER)
                        .and("status").is(UserStatus.ACTIVE)
                        .and("profileImagePublicId").is(null)
        );
        Update initUpdate = new Update()
                .set("profileImagePublicId", "photo-initial")
                .set("profileImageUrl", "https://cdn.example.com/photo-initial.jpg");

        UpdateResult initResult = mongoTemplate.updateFirst(initQuery, initUpdate, User.class);

        // Then: Successfully matches 1 document and updates
        assertEquals(1, initResult.getMatchedCount());
        assertEquals(1, initResult.getModifiedCount());

        User updatedInDb = userRepository.findById("cust-init-1").orElseThrow();
        assertEquals("photo-initial", updatedInDb.getProfileImagePublicId());
        assertEquals("https://cdn.example.com/photo-initial.jpg", updatedInDb.getProfileImageUrl());
    }

    @Test
    @DisplayName("Remove photo race: conditional unset matches expected publicId only")
    void conditionalUnset_detectsConcurrentChange_andDoesNotUnsetWrongPhoto() {
        // Given: User with photo-X
        User user = new User();
        user.setId("cust-remove-1");
        user.setName("Anura Kumara");
        user.setEmail("anura@marketplace.local");
        user.setRole(Role.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setProfileImagePublicId("photo-X");
        user.setProfileImageUrl("https://cdn.example.com/photo-X.jpg");
        user.setCreatedAt(Instant.now());
        userRepository.save(user);

        // Simulated concurrent change: photo changed to photo-Y
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is("cust-remove-1")),
                new Update().set("profileImagePublicId", "photo-Y").set("profileImageUrl", "https://cdn.example.com/photo-Y.jpg"),
                User.class
        );

        // When: Remove request that expected photo-X tries conditional unset
        Query removeQuery = Query.query(
                Criteria.where("_id").is("cust-remove-1")
                        .and("role").is(Role.CUSTOMER)
                        .and("status").is(UserStatus.ACTIVE)
                        .and("profileImagePublicId").is("photo-X")
        );
        Update removeUpdate = new Update()
                .unset("profileImageUrl")
                .unset("profileImagePublicId");

        UpdateResult removeResult = mongoTemplate.updateFirst(removeQuery, removeUpdate, User.class);

        // Then: Matched count is 0
        assertEquals(0, removeResult.getMatchedCount());

        // And: Database still has photo-Y
        User reloaded = userRepository.findById("cust-remove-1").orElseThrow();
        assertEquals("photo-Y", reloaded.getProfileImagePublicId());
        assertEquals("https://cdn.example.com/photo-Y.jpg", reloaded.getProfileImageUrl());
    }
}

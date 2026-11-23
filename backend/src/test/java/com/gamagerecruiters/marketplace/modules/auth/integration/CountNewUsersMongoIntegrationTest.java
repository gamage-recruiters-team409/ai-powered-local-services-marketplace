package com.gamagerecruiters.marketplace.modules.auth.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.Granularity;
import com.gamagerecruiters.marketplace.modules.auth.dto.PeriodCount;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

/**
 * Real MongoDB integration test verifying countNewUsers aggregation:
 * - Bucketing by WEEK (Monday start, UTC) and MONTH (1st of month, UTC)
 * - Zero-filled empty periods
 * - Chronological ordering of returned periods
 * - Exclusion of users with null/missing createdAt
 * - Restriction that ADMIN only counts CUSTOMER and PROVIDER roles
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class CountNewUsersMongoIntegrationTest {

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
        if (mongoDBContainer != null && mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private UserInternalService userInternalService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String userId, String email, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, email, role);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private User createUser(String id, String email, Role role, UserStatus status, Instant createdAt) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setName("User " + id);
        user.setRole(role);
        user.setStatus(status);
        user.setCreatedAt(createdAt);
        return userRepository.save(user);
    }

    @Test
    @DisplayName("countNewUsers WEEK: Monday start UTC, empty period zero-filled, null createdAt excluded, chronological order")
    void testCountNewUsersWeeklyBucketsAgainstRealMongo() {
        // Actors
        createUser("super-actor", "super@marketplace.local", Role.SUPER_ADMIN, UserStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"));
        createUser("admin-actor", "admin@marketplace.local", Role.ADMIN, UserStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"));

        // Week 1: 2026-10-05 (Monday) to 2026-10-12 (Monday)
        createUser("u1", "u1@test.local", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-10-07T10:00:00Z")); // Wed
        createUser("u2", "u2@test.local", Role.PROVIDER, UserStatus.ACTIVE, Instant.parse("2026-10-08T15:30:00Z")); // Thu
        createUser("u3", "u3@test.local", Role.ADMIN, UserStatus.ACTIVE, Instant.parse("2026-10-09T08:00:00Z"));    // Fri

        // Week 2: 2026-10-12 to 2026-10-19 -> EMPTY WEEK (no users created)

        // Week 3: 2026-10-19 to 2026-10-26
        createUser("u4", "u4@test.local", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-10-21T11:00:00Z")); // Wed
        createUser("u5", "u5@test.local", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-10-22T14:00:00Z")); // Thu

        // Legacy user with null createdAt -> must be excluded
        createUser("legacy-1", "legacy@test.local", Role.CUSTOMER, UserStatus.ACTIVE, null);

        // Outside query range
        createUser("past-1", "past@test.local", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-09-15T00:00:00Z"));
        createUser("future-1", "future@test.local", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-11-15T00:00:00Z"));

        // 1. Query as SUPER_ADMIN: should count all roles across all 3 weeks
        authenticateAs("super-actor", "super@marketplace.local", Role.SUPER_ADMIN);
        Instant from = Instant.parse("2026-10-05T00:00:00Z");
        Instant to = Instant.parse("2026-10-26T00:00:00Z");

        List<PeriodCount> superResults = userInternalService.countNewUsers("super-actor", null, from, to, Granularity.WEEK);

        assertNotNull(superResults);
        assertEquals(3, superResults.size(), "Should have exactly 3 weekly buckets");

        // Bucket 1 (starts Monday 2026-10-05T00:00:00Z): 3 users (CUSTOMER, PROVIDER, ADMIN)
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), superResults.get(0).periodStart());
        assertEquals(3L, superResults.get(0).count());

        // Bucket 2 (starts Monday 2026-10-12T00:00:00Z): EMPTY PERIOD ZERO-FILLED
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), superResults.get(1).periodStart());
        assertEquals(0L, superResults.get(1).count(), "Empty period must be filled with 0");

        // Bucket 3 (starts Monday 2026-10-19T00:00:00Z): 2 users (CUSTOMER, CUSTOMER)
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), superResults.get(2).periodStart());
        assertEquals(2L, superResults.get(2).count());

        // Chronological order verified
        assertTrue(superResults.get(0).periodStart().isBefore(superResults.get(1).periodStart()));
        assertTrue(superResults.get(1).periodStart().isBefore(superResults.get(2).periodStart()));

        // 2. Query as ADMIN: should ONLY count CUSTOMER and PROVIDER (ADMIN user u3 excluded from week 1)
        authenticateAs("admin-actor", "admin@marketplace.local", Role.ADMIN);
        List<PeriodCount> adminResults = userInternalService.countNewUsers("admin-actor", null, from, to, Granularity.WEEK);

        assertNotNull(adminResults);
        assertEquals(3, adminResults.size());

        // Bucket 1 for ADMIN: 2 users (CUSTOMER u1 and PROVIDER u2; ADMIN u3 is excluded)
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), adminResults.get(0).periodStart());
        assertEquals(2L, adminResults.get(0).count(), "ADMIN visibility must exclude ADMIN user u3");

        // Bucket 2 for ADMIN: 0 users
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), adminResults.get(1).periodStart());
        assertEquals(0L, adminResults.get(1).count());

        // Bucket 3 for ADMIN: 2 users (CUSTOMER u4 and CUSTOMER u5)
        assertEquals(Instant.parse("2026-10-19T00:00:00Z"), adminResults.get(2).periodStart());
        assertEquals(2L, adminResults.get(2).count());
    }

    @Test
    @DisplayName("countNewUsers MONTH: 1st of month UTC, empty month zero-filled, users with null createdAt excluded")
    void testCountNewUsersMonthlyBucketsAgainstRealMongo() {
        createUser("super-actor", "super@marketplace.local", Role.SUPER_ADMIN, UserStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"));

        // Range: 2026-07-01 to 2026-10-01 (July, August, September)
        // July: 2 users
        createUser("jul-1", "jul1@test.local", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-07-10T12:00:00Z"));
        createUser("jul-2", "jul2@test.local", Role.PROVIDER, UserStatus.ACTIVE, Instant.parse("2026-07-25T18:00:00Z"));

        // August: EMPTY MONTH (no users)

        // September: 1 user
        createUser("sep-1", "sep1@test.local", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-09-05T09:00:00Z"));

        // Null createdAt -> excluded
        createUser("no-date", "nodate@test.local", Role.CUSTOMER, UserStatus.ACTIVE, null);

        authenticateAs("super-actor", "super@marketplace.local", Role.SUPER_ADMIN);
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-01T00:00:00Z");

        List<PeriodCount> results = userInternalService.countNewUsers("super-actor", null, from, to, Granularity.MONTH);

        assertNotNull(results);
        assertEquals(3, results.size(), "Should have exactly 3 monthly buckets");

        // Month 1 (July 2026): 2 users
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), results.get(0).periodStart());
        assertEquals(2L, results.get(0).count());

        // Month 2 (August 2026): 0 users (zero-filled empty period)
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), results.get(1).periodStart());
        assertEquals(0L, results.get(1).count());

        // Month 3 (September 2026): 1 user
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), results.get(2).periodStart());
        assertEquals(1L, results.get(2).count());

        // Chronological order verified
        assertTrue(results.get(0).periodStart().isBefore(results.get(1).periodStart()));
        assertTrue(results.get(1).periodStart().isBefore(results.get(2).periodStart()));
    }

    @Test
    @DisplayName("countNewUsers ADMIN: explicitly requesting forbidden role throws 403 ROLE_NOT_PERMITTED")
    void testAdminRequestingForbiddenRoleThrows() {
        createUser("admin-actor", "admin@marketplace.local", Role.ADMIN, UserStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"));
        authenticateAs("admin-actor", "admin@marketplace.local", Role.ADMIN);

        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");

        ApiException ex = assertThrows(ApiException.class,
                () -> userInternalService.countNewUsers("admin-actor", Set.of(Role.SUPER_ADMIN), from, to, Granularity.MONTH));

        assertEquals("ROLE_NOT_PERMITTED", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }
}

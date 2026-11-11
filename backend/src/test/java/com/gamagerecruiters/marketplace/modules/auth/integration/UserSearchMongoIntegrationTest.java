package com.gamagerecruiters.marketplace.modules.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.AdminUserSummary;
import com.gamagerecruiters.marketplace.modules.auth.repository.UserRepository;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
public class UserSearchMongoIntegrationTest {

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
    private UserInternalService userInternalService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUpCleanData() {
        mongoTemplate.remove(new Query(), User.class);
    }

    @Test
    @DisplayName("Search with no filters returns all users with accurate total count")
    void searchWithoutFiltersReturnsAllUsers() {
        seedUser("u1", "Alice Smith", "alice@example.com", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-01-01T10:00:00Z"));
        seedUser("u2", "Bob Jones", "bob@example.com", Role.ADMIN, UserStatus.ACTIVE, Instant.parse("2026-01-02T10:00:00Z"));
        seedUser("u3", "Charlie Brown", "charlie@example.com", Role.PROVIDER, UserStatus.SUSPENDED, Instant.parse("2026-01-03T10:00:00Z"));

        Page<AdminUserSummary> page = userInternalService.searchUsers(null, null, null, PageRequest.of(0, 10));

        assertEquals(3, page.getTotalElements());
        assertEquals(3, page.getContent().size());
    }

    @Test
    @DisplayName("Role-set filter matches only users within the specified roles")
    void searchWithRoleSetFilter() {
        seedUser("u1", "Admin One", "admin1@test.com", Role.ADMIN, UserStatus.ACTIVE, Instant.parse("2026-01-01T10:00:00Z"));
        seedUser("u2", "Support One", "support1@test.com", Role.SUPPORT_ADMIN, UserStatus.ACTIVE, Instant.parse("2026-01-02T10:00:00Z"));
        seedUser("u3", "Customer One", "cust1@test.com", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-01-03T10:00:00Z"));

        Page<AdminUserSummary> page = userInternalService.searchUsers(
                Set.of(Role.ADMIN, Role.SUPPORT_ADMIN), null, null, PageRequest.of(0, 10)
        );

        assertEquals(2, page.getTotalElements());
        assertThat(page.getContent()).extracting(AdminUserSummary::email)
                .containsExactlyInAnyOrder("admin1@test.com", "support1@test.com");
    }

    @Test
    @DisplayName("Status filter matches only users with the given status")
    void searchWithStatusFilter() {
        seedUser("u1", "Active User", "active@test.com", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-01-01T10:00:00Z"));
        seedUser("u2", "Suspended User", "suspended@test.com", Role.CUSTOMER, UserStatus.SUSPENDED, Instant.parse("2026-01-02T10:00:00Z"));
        seedUser("u3", "Locked User", "locked@test.com", Role.CUSTOMER, UserStatus.LOCKED, Instant.parse("2026-01-03T10:00:00Z"));

        Page<AdminUserSummary> page = userInternalService.searchUsers(
                null, UserStatus.SUSPENDED, null, PageRequest.of(0, 10)
        );

        assertEquals(1, page.getTotalElements());
        assertEquals("suspended@test.com", page.getContent().get(0).email());
    }

    @Test
    @DisplayName("Case-insensitive search matches fullName and email substrings")
    void searchCaseInsensitiveNameAndEmail() {
        seedUser("u1", "Kasun Perera", "kasun@local.lk", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-01-01T10:00:00Z"));
        seedUser("u2", "Nuwan Silva", "nuwan.perera@local.lk", Role.PROVIDER, UserStatus.ACTIVE, Instant.parse("2026-01-02T10:00:00Z"));
        seedUser("u3", "Dilshan Fernando", "dilshan@other.lk", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-01-03T10:00:00Z"));

        // Match by name substring in different case: "pErErA" matches Kasun Perera (by name) and Nuwan Silva (by email)
        Page<AdminUserSummary> page = userInternalService.searchUsers(
                null, null, "  pErErA  ", PageRequest.of(0, 10)
        );

        assertEquals(2, page.getTotalElements());
        assertThat(page.getContent()).extracting(AdminUserSummary::email)
                .containsExactlyInAnyOrder("kasun@local.lk", "nuwan.perera@local.lk");
    }

    @Test
    @DisplayName("Regex special characters are treated literally and do not break or execute as regex")
    void searchRegexCharactersTreatedLiterally() {
        seedUser("u1", "Literal (Special) Name", "special(name)@test.com", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-01-01T10:00:00Z"));
        seedUser("u2", "Normal Name", "normal@test.com", Role.CUSTOMER, UserStatus.ACTIVE, Instant.parse("2026-01-02T10:00:00Z"));

        // Searching with regex parenthesis: "(Special)"
        Page<AdminUserSummary> page1 = userInternalService.searchUsers(
                null, null, "(Special)", PageRequest.of(0, 10)
        );
        assertEquals(1, page1.getTotalElements());
        assertEquals("u1", page1.getContent().get(0).id());

        // Searching with wildcard ".*": should NOT match all, only literal ".*"
        Page<AdminUserSummary> page2 = userInternalService.searchUsers(
                null, null, ".*", PageRequest.of(0, 10)
        );
        assertEquals(0, page2.getTotalElements());
    }

    @Test
    @DisplayName("Page size is capped at 100 and unpaged requests are rejected")
    void pageSizeIsCappedAt100AndUnpagedRejected() {
        for (int i = 1; i <= 105; i++) {
            seedUser("u" + i, "User " + i, "user" + i + "@test.com", Role.CUSTOMER, UserStatus.ACTIVE, Instant.now());
        }

        // Request with size 200 should be capped to 100
        Page<AdminUserSummary> page = userInternalService.searchUsers(
                null, null, null, PageRequest.of(0, 200)
        );
        assertEquals(105, page.getTotalElements());
        assertEquals(100, page.getContent().size());
        assertEquals(100, page.getPageable().getPageSize());

        // Unpaged request rejected with 400 ApiException
        assertThrows(ApiException.class, () ->
                userInternalService.searchUsers(null, null, null, Pageable.unpaged())
        );
    }

    @Test
    @DisplayName("Sort whitelist maps fullName to name, and unknown sort falls back to createdAt descending")
    void sortWhitelistAndFallback() {
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T10:00:00Z");
        Instant t3 = Instant.parse("2026-01-03T10:00:00Z");

        seedUser("u1", "Zara", "zara@test.com", Role.CUSTOMER, UserStatus.ACTIVE, t1);
        seedUser("u2", "Adam", "adam@test.com", Role.CUSTOMER, UserStatus.ACTIVE, t2);
        seedUser("u3", "Ben", "ben@test.com", Role.CUSTOMER, UserStatus.ACTIVE, t3);

        // Sort by fullName ASC -> should map to stored field "name" ASC (Adam, Ben, Zara)
        Page<AdminUserSummary> sortedByName = userInternalService.searchUsers(
                null, null, null, PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "fullName"))
        );
        assertThat(sortedByName.getContent()).extracting(AdminUserSummary::fullName)
                .containsExactly("Adam", "Ben", "Zara");

        // Sort by unknown field -> falls back to createdAt DESC (Ben=t3, Adam=t2, Zara=t1)
        Page<AdminUserSummary> fallbackSort = userInternalService.searchUsers(
                null, null, null, PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "unknownDangerousField"))
        );
        assertThat(fallbackSort.getContent()).extracting(AdminUserSummary::fullName)
                .containsExactly("Ben", "Adam", "Zara");
    }

    @Test
    @DisplayName("countByRoleAndStatus returns accurate count for given role and status")
    void countByRoleAndStatus() {
        seedUser("u1", "Admin 1", "admin1@test.com", Role.ADMIN, UserStatus.ACTIVE, Instant.now());
        seedUser("u2", "Admin 2", "admin2@test.com", Role.ADMIN, UserStatus.ACTIVE, Instant.now());
        seedUser("u3", "Admin 3", "admin3@test.com", Role.ADMIN, UserStatus.SUSPENDED, Instant.now());
        seedUser("u4", "Cust 1", "cust1@test.com", Role.CUSTOMER, UserStatus.ACTIVE, Instant.now());

        long activeAdmins = userInternalService.countByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE);
        long suspendedAdmins = userInternalService.countByRoleAndStatus(Role.ADMIN, UserStatus.SUSPENDED);
        long activeCustomers = userInternalService.countByRoleAndStatus(Role.CUSTOMER, UserStatus.ACTIVE);

        assertEquals(2, activeAdmins);
        assertEquals(1, suspendedAdmins);
        assertEquals(1, activeCustomers);
    }

    private void seedUser(String id, String name, String email, Role role, UserStatus status, Instant createdAt) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setRole(role);
        user.setStatus(status);
        user.setPasswordHash("hashed-pw");
        user.setCreatedAt(createdAt);
        userRepository.save(user);
    }
}

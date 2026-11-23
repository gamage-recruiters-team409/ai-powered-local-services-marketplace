package com.gamagerecruiters.marketplace.modules.adminusers.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminStatusChangeResponse;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAccountService;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAuditLogService;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AccountActionResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUserAccountService;
import com.gamagerecruiters.marketplace.modules.auth.domain.AuthToken;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.TokenType;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.repository.AuthTokenRepository;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.auth.service.TokenService;
import com.gamagerecruiters.marketplace.modules.listing.domain.ListingStatus;
import com.gamagerecruiters.marketplace.modules.listing.domain.ServiceListing;

/**
 * Status changes through the REAL adapters and the REAL WP01 atomic changeStatus against a throw-away MongoDB
 * (Testcontainers; refuses the shared "marketplace" database). The only test hook is a barrier inside the PENDING
 * audit insert: both admins have read the old status before either change is attempted, so the race is real and
 * deterministic, and WP01's conditional update decides the winner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@TestPropertySource(properties = {
        "app.announcement.publisher.enabled=false",
        "app.dispute.booking-sync.enabled=false",
        "app.dispute.finalizer.enabled=false"
})
class AdminUsersStatusRaceMongoIntegrationTest {

    private static final String TEST_DATABASE = "adminusers_race_it";
    private static final String FORBIDDEN_DATABASE = "marketplace";
    private static final String CUSTOMER = "65f1c0aa11bb22cc33a20001";
    private static final String PROVIDER = "65f1c0aa11bb22cc33a20002";
    private static final String ADMIN_A = "65f1c0aa11bb22cc33a20011";
    private static final String ADMIN_B = "65f1c0aa11bb22cc33a20012";
    private static final String SUPER_A = "65f1c0aa11bb22cc33a20021";
    private static final String SUPER_B = "65f1c0aa11bb22cc33a20022";
    private static final String TARGET_ADMIN = "65f1c0aa11bb22cc33a20031";
    private static final String NOTE = "Repeated no-shows reported by several customers";

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
    @Autowired
    private AdminUserAccountService accountService;
    @Autowired
    private AdminAccountService superAdminAccountService;
    @Autowired
    private AuthTokenRepository authTokenRepository;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private MockMvc mockMvc;
    @MockitoSpyBean
    private AdminAuditLogService auditLogService;

    /** Set only while a race runs: every PENDING insert waits here until both requests reached it. */
    private volatile CyclicBarrier bothPending;

    @BeforeEach
    void refuseSharedDatabaseAndClean() {
        String dbName = mongoTemplate.getDb().getName();
        if (FORBIDDEN_DATABASE.equals(dbName)) {
            throw new IllegalStateException("Refusing to run: connected to the database named \"" + FORBIDDEN_DATABASE
                    + "\". This test deletes data and must only run against a throw-away database.");
        }
        assertThat(dbName).isEqualTo(TEST_DATABASE);
        mongoTemplate.remove(new Query(), AdminAuditLog.class);
        mongoTemplate.remove(new Query(), ServiceListing.class);
        mongoTemplate.remove(new Query(), AuthToken.class);
        mongoTemplate.remove(new Query(), User.class);
        mongoTemplate.remove(new Query(), "auth_locks");
        user(ADMIN_A, Role.ADMIN, UserStatus.ACTIVE);
        user(ADMIN_B, Role.ADMIN, UserStatus.ACTIVE);
        user(SUPER_A, Role.SUPER_ADMIN, UserStatus.ACTIVE);
        user(SUPER_B, Role.SUPER_ADMIN, UserStatus.ACTIVE);
        doAnswer(invocation -> {
            CyclicBarrier barrier = bothPending;
            if (barrier != null) {
                barrier.await(20, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(auditLogService).recordPending(any());
    }

    @AfterEach
    void clearSecurity() {
        bothPending = null;
        SecurityContextHolder.clearContext();
    }

    private void user(String id, Role role, UserStatus status) {
        User u = new User();
        u.setId(id);
        u.setName("User " + id.substring(20));
        u.setEmail(id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setRole(role);
        u.setStatus(status);
        u.setEmailVerified(true);
        u.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        mongoTemplate.insert(u);
    }

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@example.test", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** Runs as that admin on its own thread (the security context is per thread). */
    private static Callable<Object> as(String userId, Role role, Callable<Object> action) {
        return () -> {
            loginAs(userId, role);
            try {
                return action.call();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    /** Both actions start together and both pass the PENDING audit insert only once both have read the status. */
    private List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        bothPending = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = List.of(pool.submit(first), pool.submit(second));
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                try {
                    results.add(f.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    results.add(e.getCause());
                }
            }
            return results;
        } finally {
            bothPending = null;
            pool.shutdownNow();
        }
    }

    private static void assertOneWinnerOneConflict(List<Object> results, Class<?> successType) {
        assertThat(results).filteredOn(successType::isInstance).as("exactly one succeeds").hasSize(1);
        assertThat(results).filteredOn(Throwable.class::isInstance).singleElement().satisfies(e -> {
            assertThat(e).isInstanceOf(ApiException.class);
            ApiException api = (ApiException) e;
            assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(api.getErrorCode()).isEqualTo("CONCURRENT_MODIFICATION");
            assertThat(api.getMessage())
                    .isEqualTo("The account status was changed by someone else. Reload the account and try again");
        });
    }

    private void assertAuditOneSuccessOneConcurrentFailure(String targetId) {
        List<AdminAuditLog> records = mongoTemplate.find(
                Query.query(Criteria.where("targetUserId").is(targetId)), AdminAuditLog.class);
        assertThat(records).hasSize(2);
        assertThat(records).extracting(AdminAuditLog::getOutcome)
                .containsExactlyInAnyOrder(AuditOutcome.SUCCESS, AuditOutcome.FAILED);
        assertThat(records).filteredOn(r -> r.getOutcome() == AuditOutcome.FAILED).singleElement()
                .satisfies(r -> assertThat(r.getFailureCode()).isEqualTo("CONCURRENT_MODIFICATION"));
        assertThat(records).extracting(AdminAuditLog::getActorId).as("one record per admin").doesNotHaveDuplicates();
    }

    private UserStatus statusOf(String id) {
        return mongoTemplate.findById(id, User.class).getStatus();
    }

    // ---------- (a) suspend race ----------

    @Test
    @DisplayName("(a) two admins suspend the same ACTIVE customer at once -> one succeeds, the other 409 "
            + "CONCURRENT_MODIFICATION; final SUSPENDED; audit one SUCCESS + one FAILED")
    void suspendRace() throws Exception {
        user(CUSTOMER, Role.CUSTOMER, UserStatus.ACTIVE);

        List<Object> results = race(
                as(ADMIN_A, Role.ADMIN, () -> accountService.suspend(ADMIN_A, Role.ADMIN, CUSTOMER,
                        "REPEATED_CANCELLATIONS", NOTE)),
                as(ADMIN_B, Role.ADMIN, () -> accountService.suspend(ADMIN_B, Role.ADMIN, CUSTOMER,
                        "POLICY_VIOLATION", null)));

        assertOneWinnerOneConflict(results, AccountActionResponse.class);
        assertThat(statusOf(CUSTOMER)).isEqualTo(UserStatus.SUSPENDED);
        assertAuditOneSuccessOneConcurrentFailure(CUSTOMER);
    }

    // ---------- (b) reactivate race ----------

    @Test
    @DisplayName("(b) two admins reactivate the same SUSPENDED customer at once -> one succeeds, the other 409; "
            + "final ACTIVE; audit one SUCCESS + one FAILED")
    void reactivateRace() throws Exception {
        user(CUSTOMER, Role.CUSTOMER, UserStatus.SUSPENDED);

        List<Object> results = race(
                as(ADMIN_A, Role.ADMIN, () -> accountService.reactivate(ADMIN_A, Role.ADMIN, CUSTOMER,
                        "Appeal accepted after review of the bookings")),
                as(ADMIN_B, Role.ADMIN, () -> accountService.reactivate(ADMIN_B, Role.ADMIN, CUSTOMER,
                        "Reinstated after a call with the customer")));

        assertOneWinnerOneConflict(results, AccountActionResponse.class);
        assertThat(statusOf(CUSTOMER)).isEqualTo(UserStatus.ACTIVE);
        assertAuditOneSuccessOneConcurrentFailure(CUSTOMER);
    }

    // ---------- (c) sessions end on suspend ----------

    @Test
    @DisplayName("(c) after suspend the customer's refresh tokens are revoked and their next authenticated call is "
            + "rejected (401)")
    void suspendRevokesSessions() throws Exception {
        user(CUSTOMER, Role.CUSTOMER, UserStatus.ACTIVE);
        Instant now = Instant.now();
        authTokenRepository.save(new AuthToken(CUSTOMER, TokenType.REFRESH, "refresh-hash-1", now.plusSeconds(3600), now));
        authTokenRepository.save(new AuthToken(CUSTOMER, TokenType.REFRESH, "refresh-hash-2", now.plusSeconds(3600), now));
        String accessToken = tokenService.generateAccessToken(CUSTOMER, CUSTOMER + "@example.test", Role.CUSTOMER);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        loginAs(ADMIN_A, Role.ADMIN);
        assertThat(accountService.suspend(ADMIN_A, Role.ADMIN, CUSTOMER, "SUSPECTED_FRAUD", null).changed()).isTrue();
        SecurityContextHolder.clearContext();

        List<AuthToken> tokens = mongoTemplate.find(Query.query(Criteria.where("userId").is(CUSTOMER)), AuthToken.class);
        assertThat(tokens).hasSize(2).allSatisfy(t -> assertThat(t.isRevoked()).as("refresh token revoked").isTrue());
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    // ---------- (d) Super Admin race ----------

    @Test
    @DisplayName("(d) two Super Admins suspend the same ACTIVE admin at once through AdminAccountService -> one "
            + "succeeds, the other 409 CONCURRENT_MODIFICATION; final SUSPENDED; audit one SUCCESS + one FAILED")
    void superAdminRace() throws Exception {
        user(TARGET_ADMIN, Role.ADMIN, UserStatus.ACTIVE);

        List<Object> results = race(
                as(SUPER_A, Role.SUPER_ADMIN, () -> superAdminAccountService.changeStatus(SUPER_A, Role.SUPER_ADMIN,
                        TARGET_ADMIN, UserStatus.SUSPENDED, "Shared credentials with a contractor")),
                as(SUPER_B, Role.SUPER_ADMIN, () -> superAdminAccountService.changeStatus(SUPER_B, Role.SUPER_ADMIN,
                        TARGET_ADMIN, UserStatus.SUSPENDED, "Policy breach reported by the team lead")));

        assertOneWinnerOneConflict(results, AdminStatusChangeResponse.class);
        assertThat(statusOf(TARGET_ADMIN)).isEqualTo(UserStatus.SUSPENDED);
        assertAuditOneSuccessOneConcurrentFailure(TARGET_ADMIN);
    }

    // ---------- provider listing visibility through the real listing module ----------

    @Test
    @DisplayName("provider suspend through the real WP03 service: ACTIVE listings become PAUSED and hidden by the "
            + "suspension; the sync endpoint's service call is idempotent")
    void providerSuspendHidesListings() {
        user(PROVIDER, Role.PROVIDER, UserStatus.ACTIVE);
        ServiceListing listing = new ServiceListing();
        listing.setId("65f1c0aa11bb22cc33a2aaaa");
        listing.setProviderId(PROVIDER);
        listing.setTitle("Deep cleaning");
        listing.setStatus(ListingStatus.ACTIVE);
        mongoTemplate.insert(listing);
        loginAs(ADMIN_A, Role.ADMIN);

        AccountActionResponse result = accountService.suspend(ADMIN_A, Role.ADMIN, PROVIDER, "POLICY_VIOLATION", null);

        assertThat(result.listingVisibilitySynced()).isTrue();
        ServiceListing stored = mongoTemplate.findById(listing.getId(), ServiceListing.class);
        assertThat(stored.getStatus()).isEqualTo(ListingStatus.PAUSED);
        assertThat(stored.isHiddenByProviderSuspension()).isTrue();
        assertThat(mongoTemplate.findById(result.auditLogId(), AdminAuditLog.class).getListingVisibilitySync())
                .as("no failure marker on success").isNull();

        assertThat(accountService.syncListingVisibility(ADMIN_A, PROVIDER).listingVisibilitySynced()).isTrue();
        assertThat(mongoTemplate.findById(listing.getId(), ServiceListing.class).getStatus())
                .isEqualTo(ListingStatus.PAUSED);
    }
}

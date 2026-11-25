package com.gamagerecruiters.marketplace.modules.adminusers.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAuditLogService;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AccountActionResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AdminUserDetailResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AdminUserRow;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDisputeStatsPort;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUserAccountService;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUsersCsvExporter;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUsersQueryService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;

/**
 * Admin users against a REAL MongoDB in a throw-away Testcontainers instance (never Atlas or the shared database):
 * the dispute stats this module reads directly from the disputes collection, the audit history read, and one
 * suspend / reactivate through the real auth module and audit log. Refuses to run against "marketplace".
 */
@SpringBootTest
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
class AdminUsersMongoIntegrationTest {

    private static final String TEST_DATABASE = "adminusers_it";
    private static final String FORBIDDEN_DATABASE = "marketplace";
    private static final String CUSTOMER = "65f1c0aa11bb22cc33a10001";
    private static final String PROVIDER = "65f1c0aa11bb22cc33a10002";
    private static final String OTHER = "65f1c0aa11bb22cc33a10003";
    private static final String ADMIN = "65f1c0aa11bb22cc33a10009";
    private static final String REASON = "Repeated no-shows reported by several customers";

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
    private UserDisputeStatsPort disputeStats;
    @Autowired
    private AdminAuditLogService auditLogService;
    @Autowired
    private AdminUserAccountService accountService;
    @Autowired
    private AdminUsersQueryService queryService;
    @Autowired
    private AdminUsersCsvExporter csvExporter;

    @BeforeEach
    void refuseSharedDatabaseAndClean() {
        String dbName = mongoTemplate.getDb().getName();
        if (FORBIDDEN_DATABASE.equals(dbName)) {
            throw new IllegalStateException("Refusing to run: connected to the database named \"" + FORBIDDEN_DATABASE
                    + "\". This test deletes data and must only run against a throw-away database.");
        }
        assertThat(dbName).isEqualTo(TEST_DATABASE);
        mongoTemplate.remove(new Query(), Dispute.class);
        mongoTemplate.remove(new Query(), AdminAuditLog.class);
        mongoTemplate.remove(new Query(), User.class);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, role + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void dispute(String customerId, String providerId, DisputeStatus status, Instant createdAt) {
        Dispute d = new Dispute();
        d.setBookingId("booking-" + createdAt.getEpochSecond());
        d.setCustomerId(customerId);
        d.setProviderId(providerId);
        d.setRaisedBy(customerId);
        d.setCategory(DisputeCategory.NO_SHOW);
        d.setStatus(status);
        d.setDescription("Private description that must not be loaded");
        d.setCreatedAt(createdAt);
        mongoTemplate.insert(d);
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

    // ---------- dispute stats (read directly from the disputes collection) ----------

    @Test
    @DisplayName("dispute stats: lifetime and open counts as customer OR provider, newest 5, user's side, others excluded")
    void disputeStats() {
        Instant t = Instant.parse("2026-11-01T00:00:00Z");
        dispute(CUSTOMER, PROVIDER, DisputeStatus.AWAITING_RESPONSE, t.plusSeconds(1));
        dispute(CUSTOMER, OTHER, DisputeStatus.UNDER_REVIEW, t.plusSeconds(2));
        dispute(CUSTOMER, PROVIDER, DisputeStatus.RESOLVED_FINAL, t.plusSeconds(3));
        dispute(CUSTOMER, OTHER, DisputeStatus.CLOSED, t.plusSeconds(4));
        dispute(CUSTOMER, PROVIDER, DisputeStatus.APPEALED, t.plusSeconds(5));
        dispute(CUSTOMER, OTHER, DisputeStatus.RESOLVED, t.plusSeconds(6));
        dispute(OTHER, CUSTOMER, DisputeStatus.UNDER_REVIEW, t.plusSeconds(7)); // the customer is the provider here
        dispute(OTHER, PROVIDER, DisputeStatus.UNDER_REVIEW, t.plusSeconds(8)); // not the customer's

        UserDisputeStatsPort.DisputeStats stats = disputeStats.stats(CUSTOMER, 5).orElseThrow();

        assertThat(stats.lifetime()).isEqualTo(7);
        assertThat(stats.open()).as("AWAITING_RESPONSE, UNDER_REVIEW x2, APPEALED, RESOLVED").isEqualTo(5);
        assertThat(stats.recent()).hasSize(5);
        assertThat(stats.recent().get(0).createdAt()).isEqualTo(t.plusSeconds(7));
        assertThat(stats.recent().get(0).side()).isEqualTo("PROVIDER");
        assertThat(stats.recent().get(1).side()).isEqualTo("CUSTOMER");
        assertThat(stats.recent().get(1).status()).isEqualTo("RESOLVED");
        assertThat(stats.recent().get(1).category()).isEqualTo("NO_SHOW");
        assertThat(stats.recent()).extracting(UserDisputeStatsPort.RecentDispute::createdAt).isSortedAccordingTo(
                java.util.Comparator.reverseOrder());

        UserDisputeStatsPort.DisputeStats none = disputeStats.stats("65f1c0aa11bb22cc33a1ffff", 5).orElseThrow();
        assertThat(none.lifetime()).isZero();
        assertThat(none.recent()).isEmpty();
    }

    // ---------- audit history read ----------

    @Test
    @DisplayName("audit history: only ADMIN_STATUS_CHANGED records of that customer/provider, newest first, max 10; "
            + "a customer cannot read it")
    void auditHistory() {
        Instant t = Instant.parse("2026-11-01T00:00:00Z");
        for (int i = 0; i < 12; i++) {
            auditLogService.recordPending(AdminAuditLog.pending("c-" + i, ADMIN, Role.ADMIN,
                    AdminAuditAction.ADMIN_STATUS_CHANGED, PROVIDER, Role.PROVIDER, UserStatus.ACTIVE,
                    UserStatus.SUSPENDED, "reason number " + i, t.plusSeconds(i)));
        }
        auditLogService.recordPending(AdminAuditLog.pending("other", ADMIN, Role.ADMIN,
                AdminAuditAction.ADMIN_STATUS_CHANGED, OTHER, Role.CUSTOMER, UserStatus.ACTIVE, UserStatus.SUSPENDED,
                "other user", t.plusSeconds(100)));
        auditLogService.recordPending(AdminAuditLog.pending("admin-target", "super", Role.SUPER_ADMIN,
                AdminAuditAction.ADMIN_STATUS_CHANGED, PROVIDER, Role.ADMIN, UserStatus.ACTIVE, UserStatus.SUSPENDED,
                "admin target record", t.plusSeconds(200)));

        loginAs(ADMIN, Role.ADMIN);
        List<AdminAuditLog> recent = auditLogService.recentStatusChangesForTarget(PROVIDER, 10);
        assertThat(recent).hasSize(10);
        assertThat(recent.get(0).getReason()).isEqualTo("reason number 11");
        assertThat(recent).allSatisfy(r -> {
            assertThat(r.getTargetUserId()).isEqualTo(PROVIDER);
            assertThat(r.getTargetRole()).isEqualTo(Role.PROVIDER);
        });

        loginAs(ADMIN, Role.SUPER_ADMIN);
        assertThat(auditLogService.recentStatusChangesForTarget(PROVIDER, 10)).as("SUPER_ADMIN read-only").hasSize(10);

        loginAs(CUSTOMER, Role.CUSTOMER);
        assertThat(catchThrowable(() -> auditLogService.recentStatusChangesForTarget(PROVIDER, 10)))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- suspend / reactivate end to end ----------

    @Test
    @DisplayName("suspend -> reactivate through the real auth module: status stored, audit SUCCESS with the reason, "
            + "shown on the account page; repeating suspend is idempotent (no second record)")
    void suspendAndReactivate() {
        user(ADMIN, Role.ADMIN, UserStatus.ACTIVE);
        user(PROVIDER, Role.PROVIDER, UserStatus.ACTIVE);
        loginAs(ADMIN, Role.ADMIN);

        AccountActionResponse suspended = accountService.suspend(ADMIN, Role.ADMIN, PROVIDER, "REPEATED_CANCELLATIONS",
                REASON);
        assertThat(suspended.changed()).isTrue();
        assertThat(mongoTemplate.findById(PROVIDER, User.class).getStatus()).isEqualTo(UserStatus.SUSPENDED);
        AdminAuditLog record = mongoTemplate.findById(suspended.auditLogId(), AdminAuditLog.class);
        assertThat(record.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(record.getActorId()).isEqualTo(ADMIN);
        assertThat(record.getReason()).isEqualTo("REPEATED_CANCELLATIONS: " + REASON);
        assertThat(record.getFromStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(record.getToStatus()).isEqualTo(UserStatus.SUSPENDED);

        AccountActionResponse again = accountService.suspend(ADMIN, Role.ADMIN, PROVIDER, "REPEATED_CANCELLATIONS", REASON);
        assertThat(again.changed()).isFalse();
        assertThat(mongoTemplate.count(Query.query(Criteria.where("targetUserId").is(PROVIDER)), AdminAuditLog.class))
                .isEqualTo(1);

        AccountActionResponse reactivated = accountService.reactivate(ADMIN, Role.ADMIN, PROVIDER,
                "Appeal accepted after review of the bookings");
        assertThat(reactivated.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(mongoTemplate.findById(PROVIDER, User.class).getStatus()).isEqualTo(UserStatus.ACTIVE);

        AdminUserDetailResponse detail = queryService.detail(PROVIDER, ADMIN, Role.ADMIN);
        assertThat(detail.adminActions().available()).isTrue();
        assertThat(detail.adminActions().data()).hasSize(2);
        assertThat(detail.adminActions().data().get(0).toStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(detail.adminActions().data().get(0).action()).isEqualTo("REACTIVATED");
        assertThat(detail.adminActions().data().get(1).action()).isEqualTo("SUSPENDED");
        assertThat(detail.adminActions().data().get(1).reasonCode()).isEqualTo("REPEATED_CANCELLATIONS");
        assertThat(detail.adminActions().data().get(1).note()).isEqualTo(REASON);
        assertThat(detail.adminActions().data().get(1).actorName()).as("admin name resolved").isEqualTo("User 0009");
        assertThat(detail.actions().canSuspend()).isTrue();
        assertThat(detail.actions().canReactivate()).isFalse();
        assertThat(detail.actions().canSyncListingVisibility()).as("ADMIN on a provider").isTrue();
        assertThat(detail.listings().available()).as("real WP03 counts").isTrue();
        assertThat(detail.listings().data().total()).isZero();
        assertThat(suspended.listingVisibilitySynced()).as("provider: listings updated after the change").isTrue();
        assertThat(detail.profile().lastActive().available()).isTrue();
        assertThat(detail.disputes().data().lifetime()).isZero();
        assertThat(detail.bookingStats().available()).as("booking stats are a stub").isFalse();
    }

    // ---------- deterministic order when createdAt ties (pagination and CSV export) ----------

    @Test
    @DisplayName("205 accounts with the SAME createdAt: list pages (size 8) and CSV export batches (100) through the "
            + "real WP01 searchUsers return every account exactly once, in the same order every time "
            + "(createdAt desc, then email)")
    void tiedCreatedAtIsPagedDeterministically() {
        user(ADMIN, Role.ADMIN, UserStatus.ACTIVE);
        Instant sameMoment = Instant.parse("2026-06-01T00:00:00Z");
        int count = 205;
        List<User> accounts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User u = new User();
            u.setId(String.format("65f1c0aa11bb22cc33%06x", 0x100000 + i));
            u.setName("Tied " + i);
            // emails inserted in a scrambled order (37 and 205 share no factor), so insertion order != email order
            u.setEmail(String.format("tied%03d@example.test", (i * 37) % count));
            u.setPasswordHash("not-a-real-hash");
            u.setRole(i % 2 == 0 ? Role.CUSTOMER : Role.PROVIDER);
            u.setStatus(UserStatus.ACTIVE);
            u.setCreatedAt(sameMoment);
            accounts.add(u);
        }
        mongoTemplate.insertAll(accounts);
        List<String> allIds = accounts.stream().map(User::getId).toList();
        loginAs(ADMIN, Role.ADMIN);

        List<AdminUserRow> firstPass = allPages(8);
        List<AdminUserRow> secondPass = allPages(8);

        assertThat(firstPass).extracting(AdminUserRow::id)
                .as("every account once, none duplicated or skipped across pages")
                .hasSize(count).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(allIds);
        assertThat(secondPass).extracting(AdminUserRow::id).as("same order on every read")
                .containsExactlyElementsOf(firstPass.stream().map(AdminUserRow::id).toList());
        assertThat(firstPass).extracting(AdminUserRow::email).as("ties broken by email A-Z").isSorted();

        AdminUsersCsvExporter.Export export = csvExporter.export(ADMIN, null, null, null);
        List<String> csvIds = Arrays.stream(new String(export.content(), StandardCharsets.UTF_8).split("\r\n"))
                .skip(1) // header
                .filter(line -> !line.isBlank())
                .map(line -> line.substring(0, line.indexOf(',')))
                .toList();
        assertThat(export.truncated()).isFalse();
        assertThat(csvIds).as("export batches: every account once, same order as the list")
                .hasSize(count).doesNotHaveDuplicates()
                .containsExactlyElementsOf(firstPass.stream().map(AdminUserRow::id).toList());
    }

    private List<AdminUserRow> allPages(int size) {
        List<AdminUserRow> rows = new ArrayList<>();
        int page = 0;
        int totalPages;
        do {
            var result = queryService.list(ADMIN, null, null, null, page, size);
            rows.addAll(result.content());
            totalPages = result.totalPages();
            page++;
        } while (page < totalPages);
        return rows;
    }
}

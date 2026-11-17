package com.gamagerecruiters.marketplace.modules.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAuditLogResponse;
import com.gamagerecruiters.marketplace.modules.admin.initializer.AdminAuditLogIndexInitializer;
import com.gamagerecruiters.marketplace.modules.admin.service.AdminAuditLogService;
import com.gamagerecruiters.marketplace.modules.admin.service.AuditCompletionException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

/**
 * admin_audit_logs against a real MongoDB in a throw-away Testcontainers instance (never the shared database).
 *
 * <p>Covers: fresh index creation, a second start with compatible indexes, a start with an incompatible
 * index of the same name (startup fails; the index is neither dropped nor changed), PENDING to SUCCESS,
 * PENDING to FAILED, reconciliation records and their filter, strict completion, and stable order for
 * identical createdAt values.</p>
 *
 * <p>Refuses to run if the connected database is named exactly "marketplace".</p>
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class AdminAuditLogMongoIntegrationTest {

    private static final String COLLECTION = "admin_audit_logs";
    private static final String TEST_DATABASE = "admin_audit_it";
    private static final String FORBIDDEN_DATABASE = "marketplace";

    /** The four indexes the initializer must create: name -> key document. */
    private static final Map<String, Document> EXPECTED_INDEXES = Map.of(
            "createdAt_-1", new Document("createdAt", -1),
            "actorId_1_createdAt_-1", new Document("actorId", 1).append("createdAt", -1),
            "targetUserId_1_createdAt_-1", new Document("targetUserId", 1).append("createdAt", -1),
            "action_1_createdAt_-1", new Document("action", 1).append("createdAt", -1));

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
    private AdminAuditLogService auditLogService;
    @Autowired
    private AdminAuditLogIndexInitializer initializer;

    @BeforeEach
    void refuseSharedDatabaseAndReset() {
        String dbName = mongoTemplate.getDb().getName();
        if (FORBIDDEN_DATABASE.equals(dbName)) {
            throw new IllegalStateException("Refusing to run: connected to the database named \"" + FORBIDDEN_DATABASE
                    + "\". This test drops " + COLLECTION + " and must only run against a throw-away database.");
        }
        assertThat(dbName).isEqualTo(TEST_DATABASE);
        mongoTemplate.getCollection(COLLECTION).drop();
        initializer.initializeIndexes();

        UserPrincipal principal = new UserPrincipal("super-it", "super-it@test.local", Role.SUPER_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private MongoCollection<Document> logs() {
        return mongoTemplate.getCollection(COLLECTION);
    }

    /** name -> full index description (as listIndexes returns it), excluding _id_. */
    private Map<String, Document> indexes() {
        Map<String, Document> byName = new TreeMap<>();
        for (Document index : logs().listIndexes()) {
            if (!"_id_".equals(index.getString("name"))) {
                byName.put(index.getString("name"), index);
            }
        }
        return byName;
    }

    private static AdminAuditLog pending(String correlationId, AdminAuditAction action, String targetUserId) {
        return AdminAuditLog.pending(correlationId, "super-it", Role.SUPER_ADMIN, action, targetUserId, Role.MODERATOR,
                UserStatus.ACTIVE, UserStatus.SUSPENDED, "Integration test reason", Instant.parse("2026-11-16T08:00:00Z"));
    }

    // ------------------------------------------------------------------ indexes

    @Test
    @DisplayName("Fresh collection: the initializer creates exactly the four expected indexes")
    void freshInitializationCreatesIndexes() {
        Map<String, Document> indexes = indexes();

        assertThat(indexes.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_INDEXES.keySet());
        EXPECTED_INDEXES.forEach((name, key) -> {
            Document actual = (Document) indexes.get(name).get("key");
            assertThat(new ArrayList<>(actual.keySet())).as(name + " key order").containsExactlyElementsOf(key.keySet());
            key.forEach((field, direction) ->
                    assertThat(((Number) actual.get(field)).intValue()).as(name + "." + field).isEqualTo(direction));
            assertThat(indexes.get(name).get("unique")).as(name + " is not unique").isNull();
        });
    }

    @Test
    @DisplayName("Second start with compatible indexes: no error, indexes unchanged, no extra index")
    void compatibleIndexesAreKept() {
        Map<String, Document> before = indexes();

        initializer.initializeIndexes();
        initializer.initializeIndexes();

        assertThat(indexes()).isEqualTo(before);
    }

    @Test
    @DisplayName("Existing incompatible index with the same name: startup fails clearly; the index is not dropped or changed")
    void incompatibleIndexIsNotDroppedOrAltered() {
        logs().drop();
        logs().createIndex(Indexes.descending("createdAt"), new IndexOptions().name("createdAt_-1").unique(true));
        Document before = indexes().get("createdAt_-1");

        assertThatThrownBy(() -> initializer.initializeIndexes())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(COLLECTION);

        Map<String, Document> after = indexes();
        assertThat(after.get("createdAt_-1")).as("incompatible index left exactly as it was").isEqualTo(before);
        assertThat(after.get("createdAt_-1").getBoolean("unique")).isTrue();
        // The initializer stops at the first conflict, so the indexes after it were not created on this run.
        assertThat(after.keySet()).containsExactly("createdAt_-1");
    }

    // ------------------------------------------------------------------ persistence

    @Test
    @DisplayName("PENDING -> SUCCESS (markCreated sets the new account id); completing again throws, record unchanged")
    void pendingToSuccess() {
        AdminAuditLog saved = auditLogService.recordPending(pending("corr-ok", AdminAuditAction.ADMIN_ACCOUNT_CREATED, null));
        assertThat(saved.getId()).isNotBlank();

        auditLogService.markCreated(saved.getId(), "new-admin-it");

        AdminAuditLog stored = mongoTemplate.findById(saved.getId(), AdminAuditLog.class);
        assertThat(stored.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(stored.getTargetUserId()).isEqualTo("new-admin-it");
        assertThat(stored.getCorrelationId()).isEqualTo("corr-ok");
        assertThat(stored.getCompletedAt()).isNotNull();

        assertThatThrownBy(() -> auditLogService.markFailed(saved.getId(), "LATE"))
                .isInstanceOf(AuditCompletionException.class)
                .extracting(e -> ((AuditCompletionException) e).getReason())
                .isEqualTo(AuditCompletionException.Reason.NOT_PENDING);
        assertThat(mongoTemplate.findById(saved.getId(), AdminAuditLog.class).getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
    }

    @Test
    @DisplayName("PENDING -> FAILED with the failure code")
    void pendingToFailed() {
        AdminAuditLog saved = auditLogService.recordPending(pending("corr-fail", AdminAuditAction.ADMIN_STATUS_CHANGED, "admin-it"));

        auditLogService.markFailed(saved.getId(), "CANNOT_MODIFY_SELF");

        AdminAuditLog stored = mongoTemplate.findById(saved.getId(), AdminAuditLog.class);
        assertThat(stored.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(stored.getFailureCode()).isEqualTo("CANNOT_MODIFY_SELF");
    }

    @Test
    @DisplayName("Completing a record that does not exist -> AuditCompletionException NOT_PENDING")
    void missingRecordIsReported() {
        assertThatThrownBy(() -> auditLogService.markSuccess("000000000000000000000000"))
                .isInstanceOf(AuditCompletionException.class);
    }

    @Test
    @DisplayName("Reconciliation record: inserted complete, linked by correlationId and relatedAuditLogId, findable by action and target")
    void reconciliationRecordIsFindable() {
        AdminAuditLog original = auditLogService.recordPending(
                pending("corr-recon", AdminAuditAction.ADMIN_ACCOUNT_CREATED, null));

        AdminAuditLog recon = auditLogService.recordReconciliation(original, "new-admin-recon", "AUDIT_COMPLETION_FAILED");

        List<AdminAuditLogResponse> byAction = auditLogService
                .search(null, null, "AUDIT_RECONCILIATION_REQUIRED", null, null, 0, 20).content();
        assertThat(byAction).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(recon.getId());
            assertThat(r.correlationId()).isEqualTo("corr-recon");
            assertThat(r.relatedAuditLogId()).isEqualTo(original.getId());
            assertThat(r.targetUserId()).isEqualTo("new-admin-recon");
            assertThat(r.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(r.failureCode()).isEqualTo("AUDIT_COMPLETION_FAILED");
            assertThat(r.reason()).isNull();
        });
        assertThat(auditLogService.search(null, "new-admin-recon", null, null, null, 0, 20).content())
                .extracting(AdminAuditLogResponse::id).containsExactly(recon.getId());
        // The original stays PENDING; the reconciliation record did not update it.
        assertThat(mongoTemplate.findById(original.getId(), AdminAuditLog.class).getOutcome()).isEqualTo(AuditOutcome.PENDING);
    }

    @Test
    @DisplayName("Identical createdAt: order is by id descending and pages neither repeat nor skip a record")
    void identicalCreatedAtIsStable() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(auditLogService.recordPending(pending("corr-" + i, AdminAuditAction.ADMIN_STATUS_CHANGED, "t-" + i)).getId());
        }
        List<String> expected = ids.stream().sorted((a, b) -> b.compareTo(a)).toList();

        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            auditLogService.search(null, null, null, null, null, page, 2).content().forEach(r -> paged.add(r.id()));
        }

        assertThat(paged).containsExactlyElementsOf(expected);
        assertThat(auditLogService.search(null, null, null, null, null, 0, 5).content())
                .extracting(AdminAuditLogResponse::id).containsExactlyElementsOf(expected);
    }
}

package com.gamagerecruiters.marketplace.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAuditLogResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminPageResponse;
import com.gamagerecruiters.marketplace.modules.admin.repository.AdminAuditLogRepository;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.mongodb.client.result.UpdateResult;

@ExtendWith(MockitoExtension.class)
class AdminAuditLogServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");

    @Mock
    private AdminAuditLogRepository repository;
    @Mock
    private MongoTemplate mongoTemplate;

    private AdminAuditLogService service;

    @BeforeEach
    void setUp() {
        service = new AdminAuditLogService(repository, mongoTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AdminAuditLog pending() {
        return AdminAuditLog.pending("corr-1", "super-1", Role.SUPER_ADMIN, AdminAuditAction.ADMIN_STATUS_CHANGED,
                "admin-7", Role.ADMIN, UserStatus.ACTIVE, UserStatus.SUSPENDED, "Repeated violations", NOW);
    }

    private static void assertInvalidFilter(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(AdminErrors.INVALID_FILTER);
    }

    @Test
    @DisplayName("recordPending inserts (never upserts) a PENDING record")
    void recordPendingInserts() {
        AdminAuditLog log = pending();
        when(repository.insert(log)).thenReturn(log);

        assertThat(service.recordPending(log)).isSameAs(log);
        verify(repository).insert(log);
    }

    @Test
    @DisplayName("markFailed updates only a record that is still PENDING and stores the failure code")
    void markFailedIsConditional() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service.markFailed("audit-1", "ACCESS_DENIED");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(query.capture(), update.capture(), eq(AdminAuditLog.class));
        Document filter = query.getValue().getQueryObject();
        assertThat(filter.get("_id")).isEqualTo("audit-1");
        assertThat(filter.get("outcome")).isEqualTo(AuditOutcome.PENDING);
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        assertThat(set.get("outcome")).isEqualTo(AuditOutcome.FAILED);
        assertThat(set.get("failureCode")).isEqualTo("ACCESS_DENIED");
        assertThat(set.get("completedAt")).isEqualTo(NOW);
        assertThat(set.keySet()).containsExactlyInAnyOrder("outcome", "failureCode", "completedAt");
    }

    @Test
    @DisplayName("markSuccess sets only outcome and completedAt")
    void markSuccessSetsOnlyOutcome() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service.markSuccess("audit-1");

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), update.capture(), eq(AdminAuditLog.class));
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        assertThat(set.keySet()).containsExactlyInAnyOrder("outcome", "completedAt");
        assertThat(set.get("outcome")).isEqualTo(AuditOutcome.SUCCESS);
    }

    @Test
    @DisplayName("markCreated sets SUCCESS and the created account id, only while PENDING")
    void markCreatedSetsTargetId() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        service.markCreated("audit-1", "new-admin-1");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(query.capture(), update.capture(), eq(AdminAuditLog.class));
        assertThat(query.getValue().getQueryObject().get("outcome")).isEqualTo(AuditOutcome.PENDING);
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        assertThat(set.keySet()).containsExactlyInAnyOrder("outcome", "completedAt", "targetUserId");
        assertThat(set.get("outcome")).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(set.get("targetUserId")).isEqualTo("new-admin-1");
    }

    @Test
    @DisplayName("search: applies every filter, newest first, and maps to DTOs")
    void searchAppliesFilters() {
        when(mongoTemplate.find(any(Query.class), eq(AdminAuditLog.class))).thenReturn(List.of(pending()));

        AdminPageResponse<AdminAuditLogResponse> result = service.search("super-1", "admin-7", "admin_status_changed",
                "2026-11-01T00:00:00Z", "2026-11-30T00:00:00Z", 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).reason()).isEqualTo("Repeated violations");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(AdminAuditLog.class));
        Document filter = query.getValue().getQueryObject();
        assertThat(filter.get("actorId")).isEqualTo("super-1");
        assertThat(filter.get("targetUserId")).isEqualTo("admin-7");
        assertThat(filter.get("action")).isEqualTo(AdminAuditAction.ADMIN_STATUS_CHANGED);
        Document createdAt = (Document) filter.get("createdAt");
        assertThat(createdAt.get("$gte")).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(createdAt.get("$lte")).isEqualTo(Instant.parse("2026-11-30T00:00:00Z"));
        assertThat(query.getValue().getSortObject().get("createdAt")).isEqualTo(-1);
        assertThat(query.getValue().getSortObject().get("_id")).isEqualTo(-1);
    }

    @Test
    @DisplayName("search: invalid action, date, date range or paging -> 400 INVALID_FILTER, no query")
    void searchRejectsInvalidFilters() {
        assertInvalidFilter(catchThrowable(() -> service.search(null, null, "DELETE_ALL", null, null, 0, 20)));
        assertInvalidFilter(catchThrowable(() -> service.search(null, null, null, "yesterday", null, 0, 20)));
        assertInvalidFilter(catchThrowable(() -> service.search(null, null, null,
                "2026-11-30T00:00:00Z", "2026-11-01T00:00:00Z", 0, 20)));
        assertInvalidFilter(catchThrowable(() -> service.search(null, null, null, null, null, -1, 20)));
        assertInvalidFilter(catchThrowable(() -> service.search(null, null, null, null, null, 0, 101)));
        assertInvalidFilter(catchThrowable(() -> service.search("x".repeat(101), null, null, null, null, 0, 20)));
        verify(mongoTemplate, never()).find(any(Query.class), eq(AdminAuditLog.class));
    }

    // ---------- Finding 1: a completion that does not apply is reported to the caller ----------

    private static AuditCompletionException completionError(Throwable thrown) {
        assertThat(thrown).isInstanceOf(AuditCompletionException.class);
        return (AuditCompletionException) thrown;
    }

    @Test
    @DisplayName("Completion: modifiedCount 0 (already completed or missing) -> AuditCompletionException NOT_PENDING")
    void completionNotPendingIsReported() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        AuditCompletionException error = completionError(catchThrowable(() -> service.markCreated("audit-1", "new-1")));

        assertThat(error.getReason()).isEqualTo(AuditCompletionException.Reason.NOT_PENDING);
        assertThat(error.getAuditLogId()).isEqualTo("audit-1");
        assertThat(error.getAttemptedOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(completionError(catchThrowable(() -> service.markSuccess("audit-1"))).getReason())
                .isEqualTo(AuditCompletionException.Reason.NOT_PENDING);
        assertThat(completionError(catchThrowable(() -> service.markFailed("audit-1", "X"))).getAttemptedOutcome())
                .isEqualTo(AuditOutcome.FAILED);
    }

    @Test
    @DisplayName("Completion: a count other than 0 or 1 -> AuditCompletionException UNEXPECTED_COUNT")
    void completionUnexpectedCountIsReported() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class)))
                .thenReturn(UpdateResult.acknowledged(2, 2L, null));

        assertThat(completionError(catchThrowable(() -> service.markSuccess("audit-1"))).getReason())
                .isEqualTo(AuditCompletionException.Reason.UNEXPECTED_COUNT);
    }

    @Test
    @DisplayName("Completion: a database exception -> AuditCompletionException DATABASE_ERROR, cause kept, message has ids only")
    void completionDatabaseErrorIsReported() {
        RuntimeException dbDown = new org.springframework.dao.DataAccessResourceFailureException("host-a:27017 timed out");
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class))).thenThrow(dbDown);

        AuditCompletionException error = completionError(catchThrowable(() -> service.markCreated("audit-1", "new-1")));

        assertThat(error.getReason()).isEqualTo(AuditCompletionException.Reason.DATABASE_ERROR);
        assertThat(error.getCause()).isSameAs(dbDown);
        assertThat(error.getMessage()).contains("audit-1").doesNotContain("host-a");
    }

    @Test
    @DisplayName("Completion: an unacknowledged write (count unknown) -> DATABASE_ERROR, never treated as success")
    void completionUnacknowledgedIsReported() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class)))
                .thenReturn(UpdateResult.unacknowledged());

        assertThat(completionError(catchThrowable(() -> service.markSuccess("audit-1"))).getReason())
                .isEqualTo(AuditCompletionException.Reason.DATABASE_ERROR);
    }

    @Test
    @DisplayName("recordReconciliation inserts (never updates) a complete record: ids, roles, statuses and the reason code only")
    void recordReconciliationInserts() {
        AdminAuditLog original = pending();
        ReflectionTestUtils.setField(original, "id", "audit-1");
        when(repository.insert(any(AdminAuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminAuditLog record = service.recordReconciliation(original, "admin-7", "AUDIT_COMPLETION_FAILED");

        verify(repository).insert(record);
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class));
        assertThat(record.getAction()).isEqualTo(AdminAuditAction.AUDIT_RECONCILIATION_REQUIRED);
        assertThat(record.getCorrelationId()).isEqualTo("corr-1");
        assertThat(record.getRelatedAuditLogId()).isEqualTo("audit-1");
        assertThat(record.getActorId()).isEqualTo("super-1");
        assertThat(record.getActorRole()).isEqualTo(Role.SUPER_ADMIN);
        assertThat(record.getTargetUserId()).isEqualTo("admin-7");
        assertThat(record.getTargetRole()).isEqualTo(Role.ADMIN);
        assertThat(record.getFromStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(record.getToStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(record.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(record.getFailureCode()).isEqualTo("AUDIT_COMPLETION_FAILED");
        assertThat(record.getCreatedAt()).isEqualTo(NOW);
        assertThat(record.getCompletedAt()).isEqualTo(NOW);
        assertThat(record.getReason()).as("free-text reason is not copied").isNull();
        assertThat(record.getId()).isNull();
    }

    @Test
    @DisplayName("search: reconciliation records can be filtered by action")
    void searchByReconciliationAction() {
        service.search(null, null, "audit_reconciliation_required", null, null, 0, 20);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(AdminAuditLog.class));
        assertThat(query.getValue().getQueryObject().get("action")).isEqualTo(AdminAuditAction.AUDIT_RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("search: records with identical createdAt are ordered by id in the same direction (stable paging)")
    void searchHasStableSecondarySort() {
        service.search(null, null, null, null, null, 1, 5);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(AdminAuditLog.class));
        Document sort = query.getValue().getSortObject();
        assertThat(List.copyOf(sort.keySet())).containsExactly("createdAt", "_id");
        assertThat(sort.get("createdAt")).isEqualTo(-1);
        assertThat(sort.get("_id")).isEqualTo(-1);
        assertThat(query.getValue().getSkip()).isEqualTo(5);
        assertThat(query.getValue().getLimit()).isEqualTo(5);
    }
}

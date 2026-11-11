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
        return AdminAuditLog.pending("super-1", Role.SUPER_ADMIN, AdminAuditAction.ADMIN_STATUS_CHANGED,
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
}

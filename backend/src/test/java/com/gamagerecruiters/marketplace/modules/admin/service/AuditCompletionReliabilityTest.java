package com.gamagerecruiters.marketplace.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAccountResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminAuditLogResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminStatusChangeResponse;
import com.gamagerecruiters.marketplace.modules.admin.dto.CreateAdminAccountRequest;
import com.gamagerecruiters.marketplace.modules.admin.repository.AdminAuditLogRepository;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.mongodb.client.result.UpdateResult;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;

/**
 * Finding 1 (audit completion reliability). The real AdminAccountService runs against the real
 * AdminAuditLogService; only MongoDB (repository and template) and the auth module are mocked, so the
 * failures come from the same place they would in production: the conditional completion update.
 *
 * <p>Expected after the action has been applied and its completion fails: the action is called exactly once
 * (no retry, no rollback), one insert-only AUDIT_RECONCILIATION_REQUIRED record is written, an ERROR log with
 * ids and codes is written, and the normal success response is returned. No password, email or database error
 * text appears in any log line or record.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditCompletionReliabilityTest {

    private static final Instant NOW = Instant.parse("2026-11-16T08:00:00Z");
    private static final String ACTOR_ID = "super-1";
    private static final String TARGET_ID = "admin-7";
    private static final String NEW_ID = "new-admin-1";
    private static final String AUDIT_ID = "audit-1";
    private static final String RECON_ID = "recon-1";
    private static final String PASSWORD = "TempPass#2026xyz";
    private static final String EMAIL = "nimal.ops@example.com";
    private static final String DB_HOST = "cluster0-shard-00-01.example.net:27017";
    private static final String REASON = "Repeated policy violations reported";

    @Mock
    private AdminAuditLogRepository repository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private UserInternalService userInternalService;

    private AdminAuditLogService auditLogService;
    private AdminAccountService service;

    /** Every record handed to repository.insert, in order. */
    private final List<AdminAuditLog> inserted = new ArrayList<>();

    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        auditLogService = spy(new AdminAuditLogService(repository, mongoTemplate, clock));
        service = new AdminAccountService(userInternalService, auditLogService, clock);

        when(repository.insert(any(AdminAuditLog.class))).thenAnswer(inv -> {
            AdminAuditLog record = inv.getArgument(0);
            inserted.add(record);
            boolean reconciliation = record.getAction() == AdminAuditAction.AUDIT_RECONCILIATION_REQUIRED;
            ReflectionTestUtils.setField(record, "id", reconciliation ? RECON_ID : AUDIT_ID);
            return record;
        });

        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
        previousLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        rootLogger.setLevel(previousLevel);
    }

    // ------------------------------------------------------------------ helpers

    private void completionReturnsModified(long count) {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class)))
                .thenReturn(UpdateResult.acknowledged(count, count, null));
    }

    private void completionThrows() {
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class)))
                .thenThrow(new DataAccessResourceFailureException("Timed out connecting to " + DB_HOST));
    }

    private static User user(String id, Role role, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private void authCreatesAccount() {
        User created = user(NEW_ID, Role.SUPPORT_ADMIN, UserStatus.ACTIVE);
        created.setName("Nimal Perera");
        created.setEmail(EMAIL);
        created.setCreatedAt(NOW);
        created.setMustChangePassword(true);
        created.setPasswordHash("$2a$10$hash-that-must-never-leak");
        when(userInternalService.createAdminAccount("Nimal Perera", EMAIL, PASSWORD, Role.SUPPORT_ADMIN)).thenReturn(created);
    }

    private AdminAccountResponse create() {
        return service.createAdminAccount(ACTOR_ID, Role.SUPER_ADMIN,
                new CreateAdminAccountRequest("Nimal Perera", EMAIL, Role.SUPPORT_ADMIN, PASSWORD));
    }

    private void authSuspends() {
        when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.MODERATOR, UserStatus.ACTIVE));
        when(userInternalService.changeStatus(anyString(), eq(TARGET_ID), any(), eq(UserStatus.SUSPENDED)))
                .thenReturn(user(TARGET_ID, Role.MODERATOR, UserStatus.SUSPENDED));
    }

    private AdminStatusChangeResponse suspend() {
        return service.changeStatus(ACTOR_ID, Role.SUPER_ADMIN, TARGET_ID, UserStatus.SUSPENDED, REASON);
    }

    private List<AdminAuditLog> reconciliationRecords() {
        return inserted.stream().filter(r -> r.getAction() == AdminAuditAction.AUDIT_RECONCILIATION_REQUIRED).toList();
    }

    private List<String> errorLines(String event) {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("event=" + event))
                .toList();
    }

    /** Password, email, password hash and database error text never appear in logs or in any inserted record. */
    private void assertNothingSensitive() {
        for (ILoggingEvent event : logs.list) {
            String text = event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
            assertThat(text).doesNotContain(PASSWORD).doesNotContain(EMAIL).doesNotContain("$2a$").doesNotContain(DB_HOST);
        }
        for (AdminAuditLog record : inserted) {
            assertThat(AdminAuditLogResponse.from(record).toString())
                    .doesNotContain(PASSWORD).doesNotContain(EMAIL).doesNotContain("$2a$").doesNotContain(DB_HOST);
        }
    }

    private void assertReconciled(AdminAuditAction originalAction, String targetUserId, Role targetRole) {
        AdminAuditLog original = inserted.get(0);
        assertThat(original.getAction()).isEqualTo(originalAction);
        assertThat(original.getCorrelationId()).isNotBlank();

        List<AdminAuditLog> reconciliations = reconciliationRecords();
        assertThat(reconciliations).hasSize(1);
        AdminAuditLog recon = reconciliations.get(0);
        assertThat(recon.getCorrelationId()).isEqualTo(original.getCorrelationId());
        assertThat(recon.getRelatedAuditLogId()).isEqualTo(AUDIT_ID);
        assertThat(recon.getActorId()).isEqualTo(ACTOR_ID);
        assertThat(recon.getTargetUserId()).isEqualTo(targetUserId);
        assertThat(recon.getTargetRole()).isEqualTo(targetRole);
        assertThat(recon.getFailureCode()).isEqualTo(AdminAccountService.AUDIT_COMPLETION_FAILED);
        assertThat(recon.getOutcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(recon.getCreatedAt()).isEqualTo(NOW);

        List<String> errors = errorLines("AUDIT_COMPLETION_FAILED");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0))
                .contains("correlationId=" + original.getCorrelationId())
                .contains("auditLogId=" + AUDIT_ID)
                .contains("action=" + originalAction)
                .contains("actorId=" + ACTOR_ID)
                .contains("targetUserId=" + targetUserId)
                .contains("targetRole=" + targetRole)
                .contains("reasonCode=AUDIT_COMPLETION_FAILED")
                .contains("reconciliationId=" + RECON_ID);
        // Only one completion attempt: nothing is retried.
        verify(mongoTemplate, times(1)).updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class));
        assertNothingSensitive();
    }

    static Stream<Arguments> completionFailures() {
        return Stream.of(
                Arguments.of("modifiedCount 0", (Failure) t -> t.completionReturnsModified(0)),
                Arguments.of("database exception", (Failure) AuditCompletionReliabilityTest::completionThrows));
    }

    @FunctionalInterface
    interface Failure {
        void arrange(AuditCompletionReliabilityTest test);
    }

    // ------------------------------------------------------------------ create

    @ParameterizedTest(name = "create: {0}")
    @MethodSource("completionFailures")
    @DisplayName("Create: completion fails after the account exists -> reconciliation record, ERROR log, 201 body, created once")
    void createCompletionFailure(String name, Failure failure) {
        authCreatesAccount();
        failure.arrange(this);

        AdminAccountResponse response = create();

        assertThat(response.id()).isEqualTo(NEW_ID);
        assertThat(response.mustChangePassword()).isTrue();
        verify(userInternalService, times(1)).createAdminAccount(anyString(), anyString(), anyString(), any());
        assertReconciled(AdminAuditAction.ADMIN_ACCOUNT_CREATED, NEW_ID, Role.SUPPORT_ADMIN);
    }

    @Test
    @DisplayName("Create: markCreated itself throws after the account exists -> same handling, created once")
    void createMarkCreatedThrows() {
        authCreatesAccount();
        doThrow(new IllegalStateException("unexpected " + DB_HOST)).when(auditLogService).markCreated(AUDIT_ID, NEW_ID);

        AdminAccountResponse response = create();

        assertThat(response.id()).isEqualTo(NEW_ID);
        verify(userInternalService, times(1)).createAdminAccount(anyString(), anyString(), anyString(), any());
        List<AdminAuditLog> reconciliations = reconciliationRecords();
        assertThat(reconciliations).hasSize(1);
        assertThat(reconciliations.get(0).getTargetUserId()).isEqualTo(NEW_ID);
        assertThat(reconciliations.get(0).getRelatedAuditLogId()).isEqualTo(AUDIT_ID);
        assertThat(errorLines("AUDIT_COMPLETION_FAILED")).singleElement().asString()
                .contains("targetUserId=" + NEW_ID).contains("cause=IllegalStateException");
        assertNothingSensitive();
    }

    @Test
    @DisplayName("Create: the reconciliation insert also fails -> fields logged at ERROR, no retry, still the 201 body")
    void createReconciliationInsertFails() {
        authCreatesAccount();
        completionReturnsModified(0);
        doThrow(new DataAccessResourceFailureException("Timed out connecting to " + DB_HOST))
                .when(auditLogService).recordReconciliation(any(), anyString(), anyString());

        AdminAccountResponse response = create();

        assertThat(response.id()).isEqualTo(NEW_ID);
        verify(userInternalService, times(1)).createAdminAccount(anyString(), anyString(), anyString(), any());
        verify(auditLogService, times(1)).recordReconciliation(any(), anyString(), anyString());
        verify(mongoTemplate, times(1)).updateFirst(any(Query.class), any(Update.class), eq(AdminAuditLog.class));
        assertThat(reconciliationRecords()).isEmpty();
        String correlationId = inserted.get(0).getCorrelationId();
        assertThat(errorLines("AUDIT_RECONCILIATION_INSERT_FAILED")).singleElement().asString()
                .contains("correlationId=" + correlationId)
                .contains("auditLogId=" + AUDIT_ID)
                .contains("actorId=" + ACTOR_ID)
                .contains("targetUserId=" + NEW_ID)
                .contains("targetRole=SUPPORT_ADMIN")
                .contains("reasonCode=AUDIT_COMPLETION_FAILED")
                .contains("cause=DataAccessResourceFailureException");
        assertThat(errorLines("AUDIT_COMPLETION_FAILED")).singleElement().asString().contains("reconciliationId=null");
        assertNothingSensitive();
    }

    @Test
    @DisplayName("Create: normal success is unchanged -> SUCCESS with the new id, no reconciliation, no ERROR")
    void createSuccessUnchanged() {
        authCreatesAccount();
        completionReturnsModified(1);

        assertThat(create().id()).isEqualTo(NEW_ID);

        assertThat(inserted).hasSize(1);
        verify(auditLogService).markCreated(AUDIT_ID, NEW_ID);
        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertNothingSensitive();
    }

    @Test
    @DisplayName("Create: rejected by auth is unchanged -> FAILED with auth's code, same exception, no reconciliation")
    void createRejectedUnchanged() {
        ResourceConflictException duplicate = new ResourceConflictException("EMAIL_ALREADY_EXISTS", "A user with this email already exists");
        when(userInternalService.createAdminAccount(anyString(), anyString(), anyString(), any())).thenThrow(duplicate);
        completionReturnsModified(1);

        assertThat(catchThrowable(this::create)).isSameAs(duplicate);

        verify(auditLogService).markFailed(AUDIT_ID, "EMAIL_ALREADY_EXISTS");
        assertThat(reconciliationRecords()).isEmpty();
        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertNothingSensitive();
    }

    @Test
    @DisplayName("Create: rejected by auth and FAILED cannot be recorded -> auth's error still returned, logged by ids, no stack/host")
    void createRejectedAndMarkFailedFails() {
        ApiException invalid = new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "invalid role");
        when(userInternalService.createAdminAccount(anyString(), anyString(), anyString(), any())).thenThrow(invalid);
        completionThrows();

        assertThat(catchThrowable(this::create)).isSameAs(invalid);

        assertThat(reconciliationRecords()).isEmpty();
        assertThat(errorLines("AUDIT_FAILED_MARK_FAILED")).singleElement().asString()
                .contains("auditLogId=" + AUDIT_ID).contains("failureCode=INVALID_ROLE").contains("cause=DATABASE_ERROR");
        assertNothingSensitive();
    }

    // ------------------------------------------------------------------ status change

    @ParameterizedTest(name = "status: {0}")
    @MethodSource("completionFailures")
    @DisplayName("Status change: completion fails after the change -> reconciliation record, ERROR log, 200 body, changed once")
    void statusCompletionFailure(String name, Failure failure) {
        authSuspends();
        failure.arrange(this);

        AdminStatusChangeResponse response = suspend();

        assertThat(response.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(response.auditLogId()).isEqualTo(AUDIT_ID);
        verify(userInternalService, times(1)).changeStatus(anyString(), eq(TARGET_ID), any(), eq(UserStatus.SUSPENDED));
        assertReconciled(AdminAuditAction.ADMIN_STATUS_CHANGED, TARGET_ID, Role.MODERATOR);
        AdminAuditLog recon = reconciliationRecords().get(0);
        assertThat(recon.getFromStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(recon.getToStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(recon.getReason()).isNull();
    }

    @Test
    @DisplayName("Status change: the reconciliation insert also fails -> fields logged at ERROR, no retry, still the 200 body")
    void statusReconciliationInsertFails() {
        authSuspends();
        completionThrows();
        doThrow(new DataAccessResourceFailureException("Timed out connecting to " + DB_HOST))
                .when(auditLogService).recordReconciliation(any(), anyString(), anyString());

        assertThat(suspend().status()).isEqualTo(UserStatus.SUSPENDED);

        verify(userInternalService, times(1)).changeStatus(anyString(), eq(TARGET_ID), any(), eq(UserStatus.SUSPENDED));
        verify(auditLogService, times(1)).recordReconciliation(any(), anyString(), anyString());
        assertThat(errorLines("AUDIT_RECONCILIATION_INSERT_FAILED")).singleElement().asString()
                .contains("targetUserId=" + TARGET_ID).contains("targetRole=MODERATOR").contains("auditLogId=" + AUDIT_ID);
        assertNothingSensitive();
    }

    @Test
    @DisplayName("Status change: normal success is unchanged -> SUCCESS, no reconciliation, no ERROR")
    void statusSuccessUnchanged() {
        authSuspends();
        completionReturnsModified(1);

        assertThat(suspend().status()).isEqualTo(UserStatus.SUSPENDED);

        assertThat(inserted).hasSize(1);
        verify(auditLogService).markSuccess(AUDIT_ID);
        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("Status change: rejected by auth is unchanged -> FAILED with auth's code, same exception, no reconciliation")
    void statusRejectedUnchanged() {
        when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.SUPER_ADMIN, UserStatus.ACTIVE));
        ApiException last = new ApiException(HttpStatus.CONFLICT, "LAST_ACTIVE_SUPER_ADMIN", "last one");
        when(userInternalService.changeStatus(anyString(), eq(TARGET_ID), any(), eq(UserStatus.SUSPENDED))).thenThrow(last);
        completionReturnsModified(1);

        assertThat(catchThrowable(this::suspend)).isSameAs(last);

        verify(auditLogService).markFailed(AUDIT_ID, "LAST_ACTIVE_SUPER_ADMIN");
        assertThat(reconciliationRecords()).isEmpty();
        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }
}

package com.gamagerecruiters.marketplace.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditAction;
import com.gamagerecruiters.marketplace.modules.admin.domain.AdminAuditLog;
import com.gamagerecruiters.marketplace.modules.admin.domain.AuditOutcome;
import com.gamagerecruiters.marketplace.modules.admin.dto.AdminStatusChangeResponse;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;

@ExtendWith(MockitoExtension.class)
class AdminAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T08:00:00Z");
    private static final String ACTOR_ID = "super-1";
    private static final String TARGET_ID = "admin-7";
    private static final String AUDIT_ID = "audit-1";
    private static final String REASON = "Repeated policy violations reported";

    @Mock
    private UserInternalService userInternalService;
    @Mock
    private AdminAuditLogService auditLogService;

    private AdminAccountService service;

    @BeforeEach
    void setUp() {
        service = new AdminAccountService(userInternalService, auditLogService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static User user(String id, Role role, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private void auditInsertReturnsId() {
        when(auditLogService.recordPending(any(AdminAuditLog.class))).thenAnswer(invocation -> {
            AdminAuditLog log = invocation.getArgument(0);
            ReflectionTestUtils.setField(log, "id", AUDIT_ID);
            return log;
        });
    }

    private AdminStatusChangeResponse suspend(String targetId) {
        return service.changeStatus(ACTOR_ID, Role.SUPER_ADMIN, targetId, UserStatus.SUSPENDED, REASON);
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    private void assertNoAuditAndNoChange() {
        verify(auditLogService, never()).recordPending(any());
        verify(userInternalService, never()).changeStatus(anyString(), any());
    }

    // ---------- success ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("Suspends an admin account: PENDING record first, then auth changeStatus, then SUCCESS")
    void suspendsAdminAccount(Role targetRole) {
        when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, targetRole, UserStatus.ACTIVE));
        auditInsertReturnsId();
        when(userInternalService.changeStatus(TARGET_ID, UserStatus.SUSPENDED))
                .thenReturn(user(TARGET_ID, targetRole, UserStatus.SUSPENDED));

        AdminStatusChangeResponse result = suspend(TARGET_ID);

        assertThat(result.userId()).isEqualTo(TARGET_ID);
        assertThat(result.role()).isEqualTo(targetRole);
        assertThat(result.previousStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(result.auditLogId()).isEqualTo(AUDIT_ID);

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditLogService).recordPending(captor.capture());
        AdminAuditLog record = captor.getValue();
        assertThat(record.getOutcome()).isEqualTo(AuditOutcome.PENDING);
        assertThat(record.getAction()).isEqualTo(AdminAuditAction.ADMIN_STATUS_CHANGED);
        assertThat(record.getActorId()).isEqualTo(ACTOR_ID);
        assertThat(record.getActorRole()).isEqualTo(Role.SUPER_ADMIN);
        assertThat(record.getTargetUserId()).isEqualTo(TARGET_ID);
        assertThat(record.getTargetRole()).isEqualTo(targetRole);
        assertThat(record.getFromStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(record.getToStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(record.getReason()).isEqualTo(REASON);
        assertThat(record.getCreatedAt()).isEqualTo(NOW);

        verify(auditLogService).markSuccess(AUDIT_ID);
        verify(auditLogService, never()).markFailed(anyString(), anyString());
    }

    @Test
    @DisplayName("Reactivates a SUSPENDED admin; the reason is stored trimmed")
    void reactivatesAdmin() {
        when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.MODERATOR, UserStatus.SUSPENDED));
        auditInsertReturnsId();
        when(userInternalService.changeStatus(TARGET_ID, UserStatus.ACTIVE))
                .thenReturn(user(TARGET_ID, Role.MODERATOR, UserStatus.ACTIVE));

        AdminStatusChangeResponse result = service.changeStatus(
                ACTOR_ID, Role.SUPER_ADMIN, TARGET_ID, UserStatus.ACTIVE, "   Appeal accepted by the board   ");

        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditLogService).recordPending(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("Appeal accepted by the board");
        verify(auditLogService).markSuccess(AUDIT_ID);
    }

    // ---------- rejected before any audit record ----------

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"LOCKED", "DEACTIVATED", "PENDING_VERIFICATION"})
    @NullSource
    @DisplayName("Rejects statuses other than ACTIVE/SUSPENDED with 400 STATUS_NOT_ALLOWED; no audit, no change")
    void rejectsDisallowedStatus(UserStatus status) {
        Throwable thrown = catchThrowable(
                () -> service.changeStatus(ACTOR_ID, Role.SUPER_ADMIN, TARGET_ID, status, REASON));

        assertApiError(thrown, HttpStatus.BAD_REQUEST, AdminErrors.STATUS_NOT_ALLOWED);
        verifyNoInteractions(userInternalService, auditLogService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "too short", "         x         "})
    @DisplayName("Rejects a missing or short reason (under 10 characters after trimming) with 400 INVALID_REASON")
    void rejectsShortReason(String reason) {
        Throwable thrown = catchThrowable(
                () -> service.changeStatus(ACTOR_ID, Role.SUPER_ADMIN, TARGET_ID, UserStatus.SUSPENDED, reason));

        assertApiError(thrown, HttpStatus.BAD_REQUEST, AdminErrors.INVALID_REASON);
        verifyNoInteractions(userInternalService, auditLogService);
    }

    @Test
    @DisplayName("Rejects a reason over 500 characters with 400 INVALID_REASON")
    void rejectsLongReason() {
        Throwable thrown = catchThrowable(() -> service.changeStatus(
                ACTOR_ID, Role.SUPER_ADMIN, TARGET_ID, UserStatus.SUSPENDED, "x".repeat(501)));

        assertApiError(thrown, HttpStatus.BAD_REQUEST, AdminErrors.INVALID_REASON);
        verifyNoInteractions(userInternalService, auditLogService);
    }

    @Test
    @DisplayName("Rejects changing your own status with 403 SELF_STATUS_CHANGE")
    void rejectsSelfChange() {
        Throwable thrown = catchThrowable(() -> suspend(ACTOR_ID));

        assertApiError(thrown, HttpStatus.FORBIDDEN, AdminErrors.SELF_STATUS_CHANGE);
        verifyNoInteractions(userInternalService, auditLogService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CUSTOMER", "PROVIDER"})
    @DisplayName("Rejects non-admin targets with 404 ADMIN_ACCOUNT_NOT_FOUND")
    void rejectsNonAdminTarget(Role role) {
        when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, role, UserStatus.ACTIVE));

        assertApiError(catchThrowable(() -> suspend(TARGET_ID)), HttpStatus.NOT_FOUND, AdminErrors.ADMIN_ACCOUNT_NOT_FOUND);
        assertNoAuditAndNoChange();
    }

    @Test
    @DisplayName("Rejects setting the status the account already has with 400 STATUS_UNCHANGED")
    void rejectsUnchangedStatus() {
        when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.ADMIN, UserStatus.SUSPENDED));

        assertApiError(catchThrowable(() -> suspend(TARGET_ID)), HttpStatus.BAD_REQUEST, AdminErrors.STATUS_UNCHANGED);
        assertNoAuditAndNoChange();
    }

    @Test
    @DisplayName("Unknown user: auth's 404 USER_NOT_FOUND passes through unchanged, no audit")
    void unknownUserPassesThrough() {
        ResourceNotFoundException notFound = new ResourceNotFoundException("USER_NOT_FOUND", "User not found");
        when(userInternalService.getById(TARGET_ID)).thenThrow(notFound);

        assertThat(catchThrowable(() -> suspend(TARGET_ID))).isSameAs(notFound);
        assertNoAuditAndNoChange();
    }

    @Test
    @DisplayName("If the PENDING record cannot be written, the status is not changed")
    void auditInsertFailureBlocksChange() {
        when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.ADMIN, UserStatus.ACTIVE));
        when(auditLogService.recordPending(any())).thenThrow(new IllegalStateException("db down"));

        assertThat(catchThrowable(() -> suspend(TARGET_ID))).isInstanceOf(IllegalStateException.class);
        verify(userInternalService, never()).changeStatus(anyString(), any());
    }

    // ---------- rejected by the auth module: FAILED record, original error re-thrown ----------

    @Test
    @DisplayName("Last active SUPER_ADMIN rejected by auth (today 403 AccessDeniedException): FAILED record, same error re-thrown")
    void lastSuperAdminRejectedByAuth() {
        when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.SUPER_ADMIN, UserStatus.ACTIVE));
        auditInsertReturnsId();
        AccessDeniedException denied = new AccessDeniedException("last super admin");
        when(userInternalService.changeStatus(TARGET_ID, UserStatus.SUSPENDED)).thenThrow(denied);

        assertThat(catchThrowable(() -> suspend(TARGET_ID))).isSameAs(denied);
        verify(auditLogService).markFailed(AUDIT_ID, "ACCESS_DENIED");
        verify(auditLogService, never()).markSuccess(anyString());
    }

    @Test
    @DisplayName("When auth returns 409 with its own code (planned for last SUPER_ADMIN), it passes through and is recorded")
    void authConflictCodePassesThrough() {
        when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.SUPER_ADMIN, UserStatus.ACTIVE));
        auditInsertReturnsId();
        ResourceConflictException conflict = new ResourceConflictException("LAST_ACTIVE_SUPER_ADMIN", "Cannot suspend");
        when(userInternalService.changeStatus(TARGET_ID, UserStatus.SUSPENDED)).thenThrow(conflict);

        Throwable thrown = catchThrowable(() -> suspend(TARGET_ID));

        assertThat(thrown).isSameAs(conflict);
        assertApiError(thrown, HttpStatus.CONFLICT, "LAST_ACTIVE_SUPER_ADMIN");
        verify(auditLogService).markFailed(AUDIT_ID, "LAST_ACTIVE_SUPER_ADMIN");
    }

    @Test
    @DisplayName("If SUCCESS cannot be recorded after the change, the change is still reported (record stays PENDING)")
    void successMarkFailureDoesNotHideChange() {
        when(userInternalService.getById(TARGET_ID)).thenReturn(user(TARGET_ID, Role.ADMIN, UserStatus.ACTIVE));
        auditInsertReturnsId();
        when(userInternalService.changeStatus(TARGET_ID, UserStatus.SUSPENDED))
                .thenReturn(user(TARGET_ID, Role.ADMIN, UserStatus.SUSPENDED));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(auditLogService).markSuccess(AUDIT_ID);

        assertThat(suspend(TARGET_ID).status()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    @DisplayName("failureCodeOf reads the code from the exception type, never from the message")
    void failureCodeMapping() {
        assertThat(AdminErrors.failureCodeOf(new ResourceConflictException("SOME_CODE", "msg"))).isEqualTo("SOME_CODE");
        assertThat(AdminErrors.failureCodeOf(new AccessDeniedException("Self status modification is not allowed")))
                .isEqualTo("ACCESS_DENIED");
        assertThat(AdminErrors.failureCodeOf(new IllegalStateException("anything"))).isEqualTo("UNEXPECTED_ERROR");
    }
}

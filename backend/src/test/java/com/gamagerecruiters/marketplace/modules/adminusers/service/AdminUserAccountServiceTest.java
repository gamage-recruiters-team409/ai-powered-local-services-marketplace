package com.gamagerecruiters.marketplace.modules.adminusers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AccountActionResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.port.AccountActionAuditPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort.UserAccount;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class AdminUserAccountServiceTest {

    private static final String ACTOR = "admin-1";
    private static final String TARGET = "65f1c0aa11bb22cc33a1b2c3";
    private static final String REASON = "Repeated no-shows reported by several customers";
    private static final String EMAIL = "provider@example.test";

    private UserDirectoryPort directory;
    private AccountActionAuditPort audit;
    private AccountActionAuditPort.Ticket ticket;
    private AdminUserAccountService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        directory = mock(UserDirectoryPort.class);
        audit = mock(AccountActionAuditPort.class);
        ticket = mock(AccountActionAuditPort.Ticket.class);
        when(ticket.auditLogId()).thenReturn("audit-1");
        when(audit.begin(any())).thenReturn(ticket);
        when(directory.supportsAccountActions()).thenReturn(true);
        service = new AdminUserAccountService(directory, audit);
        logger = (Logger) LoggerFactory.getLogger(AdminUserAccountService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private UserAccount target(Role role, UserStatus status) {
        UserAccount account = new UserAccount(TARGET, "Kamal Silva", EMAIL, "+94771112223", role, status, true,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(directory.findById(TARGET)).thenReturn(Optional.of(account));
        return account;
    }

    private void changeReturns(Role role, UserStatus status) {
        when(directory.changeStatus(TARGET, status)).thenReturn(new UserAccount(TARGET, "Kamal Silva", EMAIL,
                "+94771112223", role, status, true, Instant.parse("2026-01-01T00:00:00Z")));
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("suspend: audit PENDING first (actor, target, from/to, reason), then the change, then SUCCESS")
    void suspendHappyPath() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        changeReturns(Role.PROVIDER, UserStatus.SUSPENDED);

        AccountActionResponse result = service.suspend(ACTOR, Role.ADMIN, TARGET, "  " + REASON + "  ");

        assertThat(result.changed()).isTrue();
        assertThat(result.previousStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(result.auditLogId()).isEqualTo("audit-1");
        ArgumentCaptor<AccountActionAuditPort.StatusChange> change =
                ArgumentCaptor.forClass(AccountActionAuditPort.StatusChange.class);
        verify(audit).begin(change.capture());
        assertThat(change.getValue()).isEqualTo(new AccountActionAuditPort.StatusChange(ACTOR, Role.ADMIN, TARGET,
                Role.PROVIDER, UserStatus.ACTIVE, UserStatus.SUSPENDED, REASON));
        verify(directory).changeStatus(TARGET, UserStatus.SUSPENDED);
        verify(ticket).succeeded();
        verify(ticket, never()).failed(anyString());
    }

    @Test
    @DisplayName("reactivate: SUSPENDED -> ACTIVE, audited")
    void reactivateHappyPath() {
        target(Role.CUSTOMER, UserStatus.SUSPENDED);
        changeReturns(Role.CUSTOMER, UserStatus.ACTIVE);

        AccountActionResponse result = service.reactivate(ACTOR, Role.ADMIN, TARGET, REASON);

        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        verify(ticket).succeeded();
    }

    @Test
    @DisplayName("idempotent: suspending a suspended (or reactivating an active) account returns the current state, "
            + "no change and no audit record")
    void idempotent() {
        target(Role.PROVIDER, UserStatus.SUSPENDED);
        AccountActionResponse again = service.suspend(ACTOR, Role.ADMIN, TARGET, REASON);
        assertThat(again.changed()).isFalse();
        assertThat(again.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(again.auditLogId()).isNull();

        target(Role.CUSTOMER, UserStatus.ACTIVE);
        assertThat(service.reactivate(ACTOR, Role.ADMIN, TARGET, REASON).changed()).isFalse();

        verify(directory, never()).changeStatus(anyString(), any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("own account -> 403 USER_ACTION_NOT_ALLOWED, nothing read or changed")
    void self() {
        assertApiError(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, ACTOR, REASON)),
                HttpStatus.FORBIDDEN, "USER_ACTION_NOT_ALLOWED");
        verify(directory, never()).findById(anyString());
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("admin-role target -> 403 USER_ACTION_NOT_ALLOWED, nothing changed")
    void adminTarget(Role role) {
        target(role, UserStatus.ACTIVE);

        assertApiError(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, TARGET, REASON)),
                HttpStatus.FORBIDDEN, "USER_ACTION_NOT_ALLOWED");
        verify(directory, never()).changeStatus(anyString(), any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("unknown user -> 404 USER_NOT_FOUND")
    void unknownUser() {
        when(directory.findById(TARGET)).thenReturn(Optional.empty());

        assertApiError(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, TARGET, REASON)),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertApiError(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, "x".repeat(65), REASON)),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "         ", "too short", "   123456789   "})
    @DisplayName("reason missing or under 10 characters (after trimming) -> 400 VALIDATION_FAILED")
    void reasonTooShort(String reason) {
        assertApiError(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, TARGET, reason)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("reason: exactly 10 and 500 characters are accepted, 501 -> 400")
    void reasonBounds() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        changeReturns(Role.PROVIDER, UserStatus.SUSPENDED);
        assertThat(service.suspend(ACTOR, Role.ADMIN, TARGET, "1234567890").changed()).isTrue();
        assertThat(service.suspend(ACTOR, Role.ADMIN, TARGET, "r".repeat(500)).changed()).isTrue();
        assertApiError(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, TARGET, "r".repeat(501))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("user directory is a stub (no account actions) -> 503 USER_ACCOUNT_ACTION_UNAVAILABLE, nothing changed")
    void stubDirectory() {
        when(directory.supportsAccountActions()).thenReturn(false);

        assertApiError(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, TARGET, REASON)),
                HttpStatus.SERVICE_UNAVAILABLE, "USER_ACCOUNT_ACTION_UNAVAILABLE");
        assertApiError(catchThrowable(() -> service.reactivate(ACTOR, Role.ADMIN, TARGET, REASON)),
                HttpStatus.SERVICE_UNAVAILABLE, "USER_ACCOUNT_ACTION_UNAVAILABLE");
        verify(directory, never()).findById(anyString());
        verify(directory, never()).changeStatus(anyString(), any());
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"PENDING_VERIFICATION", "LOCKED", "DEACTIVATED"})
    @DisplayName("reactivate only from SUSPENDED (never skips email verification or an automatic lock) -> 409")
    void reactivateOnlySuspended(UserStatus status) {
        target(Role.CUSTOMER, status);

        assertApiError(catchThrowable(() -> service.reactivate(ACTOR, Role.ADMIN, TARGET, REASON)),
                HttpStatus.CONFLICT, "USER_STATUS_NOT_CHANGEABLE");
        verifyNoInteractions(audit);
    }

    private static AccountActionAuditPort.AdminAction suspension(UserStatus from, String outcome) {
        return new AccountActionAuditPort.AdminAction("a", ACTOR, "ADMIN_STATUS_CHANGED", from, UserStatus.SUSPENDED,
                REASON, outcome, Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    @DisplayName("reactivate an account that was suspended while PENDING_VERIFICATION -> 409 (never skips email "
            + "verification), nothing changed")
    void reactivateSuspendedWhilePending() {
        target(Role.CUSTOMER, UserStatus.SUSPENDED);
        when(audit.recentForTarget(TARGET, AdminUserAccountService.AUDIT_LOOKBACK)).thenReturn(Optional.of(List.of(
                suspension(UserStatus.PENDING_VERIFICATION, "SUCCESS"), suspension(UserStatus.ACTIVE, "SUCCESS"))));

        assertApiError(catchThrowable(() -> service.reactivate(ACTOR, Role.ADMIN, TARGET, REASON)),
                HttpStatus.CONFLICT, "USER_STATUS_NOT_CHANGEABLE");
        verify(directory, never()).changeStatus(anyString(), any());
        verify(audit, never()).begin(any());
    }

    @Test
    @DisplayName("reactivate with emailVerified=false but suspended from ACTIVE (verification turned off in dev) -> "
            + "allowed; a FAILED pending-status record does not count")
    void reactivateDevAccountWithoutVerifiedEmail() {
        when(directory.findById(TARGET)).thenReturn(Optional.of(new UserAccount(TARGET, "Kamal Silva", EMAIL,
                "+94771112223", Role.CUSTOMER, UserStatus.SUSPENDED, false, Instant.parse("2026-01-01T00:00:00Z"))));
        when(audit.recentForTarget(TARGET, AdminUserAccountService.AUDIT_LOOKBACK)).thenReturn(Optional.of(List.of(
                suspension(UserStatus.PENDING_VERIFICATION, "FAILED"), suspension(UserStatus.ACTIVE, "SUCCESS"))));
        changeReturns(Role.CUSTOMER, UserStatus.ACTIVE);

        assertThat(service.reactivate(ACTOR, Role.ADMIN, TARGET, REASON).status()).isEqualTo(UserStatus.ACTIVE);
        verify(ticket).succeeded();
    }

    @Test
    @DisplayName("suspending a DEACTIVATED (self-closed) account -> 409")
    void suspendDeactivated() {
        target(Role.CUSTOMER, UserStatus.DEACTIVATED);

        assertApiError(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, TARGET, REASON)),
                HttpStatus.CONFLICT, "USER_STATUS_NOT_CHANGEABLE");
    }

    @Test
    @DisplayName("the auth module rejects the change -> audit FAILED with its code, the error passes through unchanged")
    void authRejects() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        ApiException rejected = new ApiException(HttpStatus.FORBIDDEN, "ROLE_HIERARCHY_VIOLATION", "no");
        when(directory.changeStatus(TARGET, UserStatus.SUSPENDED)).thenThrow(rejected);

        assertThat(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, TARGET, REASON))).isSameAs(rejected);
        verify(ticket).failed("ROLE_HIERARCHY_VIOLATION");
        verify(ticket, never()).succeeded();
    }

    @Test
    @DisplayName("the PENDING audit record cannot be written -> the change does not run")
    void auditInsertFails() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        when(audit.begin(any())).thenThrow(new IllegalStateException("audit down"));

        assertThat(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, TARGET, REASON)))
                .isInstanceOf(IllegalStateException.class);
        verify(directory, never()).changeStatus(anyString(), any());
    }

    @Test
    @DisplayName("logs contain ids and statuses only: never the email, phone or reason")
    void logsHaveNoPersonalData() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        changeReturns(Role.PROVIDER, UserStatus.SUSPENDED);

        service.suspend(ACTOR, Role.ADMIN, TARGET, REASON);

        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).contains(TARGET)
                    .doesNotContain(EMAIL).doesNotContain("+9477").doesNotContain(REASON);
        }
    }
}

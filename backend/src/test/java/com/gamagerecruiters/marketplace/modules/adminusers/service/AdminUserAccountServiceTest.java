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
import com.gamagerecruiters.marketplace.modules.adminusers.domain.SuspensionReason;
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
    private static final String CODE = "REPEATED_CANCELLATIONS";
    private static final String NOTE = "Cancelled five confirmed bookings this month";
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

    private void target(Role role, UserStatus status) {
        when(directory.findById(TARGET)).thenReturn(Optional.of(new UserAccount(TARGET, "Kamal Silva", EMAIL,
                "+94771112223", role, status, true, Instant.parse("2026-01-01T00:00:00Z"))));
    }

    private void changeReturns(Role role, UserStatus status) {
        when(directory.changeStatus(TARGET, status)).thenReturn(new UserAccount(TARGET, "Kamal Silva", EMAIL,
                "+94771112223", role, status, true, Instant.parse("2026-01-01T00:00:00Z")));
    }

    private AccountActionResponse suspend(String code, String note) {
        return service.suspend(ACTOR, Role.ADMIN, TARGET, code, note);
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    private String auditedReason() {
        ArgumentCaptor<AccountActionAuditPort.StatusChange> change =
                ArgumentCaptor.forClass(AccountActionAuditPort.StatusChange.class);
        verify(audit).begin(change.capture());
        return change.getValue().reason();
    }

    // ---------- suspend: reason code and note ----------

    @Test
    @DisplayName("suspend: audit PENDING first (actor, target, from/to, \"CODE: note\"), then the change, then SUCCESS")
    void suspendHappyPath() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        changeReturns(Role.PROVIDER, UserStatus.SUSPENDED);

        AccountActionResponse result = suspend(" repeated_cancellations ", "  " + NOTE + "  ");

        assertThat(result.changed()).isTrue();
        assertThat(result.previousStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(result.auditLogId()).isEqualTo("audit-1");
        ArgumentCaptor<AccountActionAuditPort.StatusChange> change =
                ArgumentCaptor.forClass(AccountActionAuditPort.StatusChange.class);
        verify(audit).begin(change.capture());
        assertThat(change.getValue()).isEqualTo(new AccountActionAuditPort.StatusChange(ACTOR, Role.ADMIN, TARGET,
                Role.PROVIDER, UserStatus.ACTIVE, UserStatus.SUSPENDED, CODE + ": " + NOTE));
        verify(directory).changeStatus(TARGET, UserStatus.SUSPENDED);
        verify(ticket).succeeded();
        verify(ticket, never()).failed(anyString());
    }

    @Test
    @DisplayName("suspend with a reason code and no note: audited as the code alone; codec reads it back")
    void suspendCodeOnly() {
        target(Role.CUSTOMER, UserStatus.ACTIVE);
        changeReturns(Role.CUSTOMER, UserStatus.SUSPENDED);

        suspend("POLICY_VIOLATION", null);

        String reason = auditedReason();
        assertThat(reason).isEqualTo("POLICY_VIOLATION");
        assertThat(SuspensionReason.codeOf(reason)).contains(SuspensionReason.POLICY_VIOLATION);
        assertThat(SuspensionReason.noteOf(reason)).isNull();
        assertThat(SuspensionReason.noteOf("OTHER: free text")).isEqualTo("free text");
        assertThat(SuspensionReason.noteOf("Plain reactivation note")).isEqualTo("Plain reactivation note");
        assertThat(SuspensionReason.codeOf("Plain reactivation note")).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "BANNED", "fraud"})
    @DisplayName("suspend: reasonCode missing or unknown -> 400 VALIDATION_FAILED, nothing read")
    void suspendBadCode(String code) {
        assertApiError(catchThrowable(() -> suspend(code, NOTE)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        verify(directory, never()).findById(anyString());
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "too short", "   123456789   "})
    @DisplayName("suspend OTHER: note required, 10 to 500 characters -> 400")
    void suspendOtherNeedsNote(String note) {
        assertApiError(catchThrowable(() -> suspend("OTHER", note)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("suspend: note over 500 characters -> 400 for any code; OTHER with 10 or 500 characters is accepted")
    void suspendNoteBounds() {
        assertApiError(catchThrowable(() -> suspend(CODE, "n".repeat(501))), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");
        target(Role.PROVIDER, UserStatus.ACTIVE);
        changeReturns(Role.PROVIDER, UserStatus.SUSPENDED);
        assertThat(suspend("OTHER", "1234567890").changed()).isTrue();
        assertThat(suspend("OTHER", "n".repeat(500)).changed()).isTrue();
    }

    // ---------- reactivate: note ----------

    @Test
    @DisplayName("reactivate: SUSPENDED -> ACTIVE, audited with the note")
    void reactivateHappyPath() {
        target(Role.CUSTOMER, UserStatus.SUSPENDED);
        changeReturns(Role.CUSTOMER, UserStatus.ACTIVE);

        AccountActionResponse result = service.reactivate(ACTOR, Role.ADMIN, TARGET, NOTE);

        assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(auditedReason()).isEqualTo(NOTE);
        verify(ticket).succeeded();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "         ", "too short", "   123456789   "})
    @DisplayName("reactivate: note missing or under 10 characters (after trimming) -> 400 VALIDATION_FAILED")
    void reactivateNoteTooShort(String note) {
        assertApiError(catchThrowable(() -> service.reactivate(ACTOR, Role.ADMIN, TARGET, note)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("reactivate: note of exactly 10 and 500 characters accepted, 501 -> 400")
    void reactivateNoteBounds() {
        target(Role.PROVIDER, UserStatus.SUSPENDED);
        changeReturns(Role.PROVIDER, UserStatus.ACTIVE);
        assertThat(service.reactivate(ACTOR, Role.ADMIN, TARGET, "1234567890").changed()).isTrue();
        assertThat(service.reactivate(ACTOR, Role.ADMIN, TARGET, "r".repeat(500)).changed()).isTrue();
        assertApiError(catchThrowable(() -> service.reactivate(ACTOR, Role.ADMIN, TARGET, "r".repeat(501))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    // ---------- account rules ----------

    @Test
    @DisplayName("idempotent: suspending a suspended (or reactivating an active) account returns the current state, "
            + "no change and no audit record")
    void idempotent() {
        target(Role.PROVIDER, UserStatus.SUSPENDED);
        AccountActionResponse again = suspend(CODE, NOTE);
        assertThat(again.changed()).isFalse();
        assertThat(again.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(again.auditLogId()).isNull();

        target(Role.CUSTOMER, UserStatus.ACTIVE);
        assertThat(service.reactivate(ACTOR, Role.ADMIN, TARGET, NOTE).changed()).isFalse();

        verify(directory, never()).changeStatus(anyString(), any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("own account -> 403 USER_ACTION_NOT_ALLOWED, nothing read or changed")
    void self() {
        assertApiError(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, ACTOR, CODE, NOTE)),
                HttpStatus.FORBIDDEN, "USER_ACTION_NOT_ALLOWED");
        verify(directory, never()).findById(anyString());
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN", "SUPPORT_ADMIN", "MODERATOR", "FINANCE_ADMIN"})
    @DisplayName("admin-role target -> 403 USER_ACTION_NOT_ALLOWED, nothing changed")
    void adminTarget(Role role) {
        target(role, UserStatus.ACTIVE);

        assertApiError(catchThrowable(() -> suspend(CODE, NOTE)), HttpStatus.FORBIDDEN, "USER_ACTION_NOT_ALLOWED");
        verify(directory, never()).changeStatus(anyString(), any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("unknown user -> 404 USER_NOT_FOUND")
    void unknownUser() {
        when(directory.findById(TARGET)).thenReturn(Optional.empty());

        assertApiError(catchThrowable(() -> suspend(CODE, NOTE)), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertApiError(catchThrowable(() -> service.suspend(ACTOR, Role.ADMIN, "x".repeat(65), CODE, NOTE)),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    @Test
    @DisplayName("user directory is a stub (no account actions) -> 503 USER_ACCOUNT_ACTION_UNAVAILABLE, nothing changed")
    void stubDirectory() {
        when(directory.supportsAccountActions()).thenReturn(false);

        assertApiError(catchThrowable(() -> suspend(CODE, NOTE)), HttpStatus.SERVICE_UNAVAILABLE,
                "USER_ACCOUNT_ACTION_UNAVAILABLE");
        assertApiError(catchThrowable(() -> service.reactivate(ACTOR, Role.ADMIN, TARGET, NOTE)),
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

        assertApiError(catchThrowable(() -> service.reactivate(ACTOR, Role.ADMIN, TARGET, NOTE)),
                HttpStatus.CONFLICT, "USER_STATUS_NOT_CHANGEABLE");
        verifyNoInteractions(audit);
    }

    private static AccountActionAuditPort.AdminAction suspension(UserStatus from, String outcome) {
        return new AccountActionAuditPort.AdminAction("a", ACTOR, "ADMIN_STATUS_CHANGED", from, UserStatus.SUSPENDED,
                CODE, outcome, Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    @DisplayName("reactivate an account that was suspended while PENDING_VERIFICATION -> 409, nothing changed")
    void reactivateSuspendedWhilePending() {
        target(Role.CUSTOMER, UserStatus.SUSPENDED);
        when(audit.recentForTarget(TARGET, AdminUserAccountService.AUDIT_LOOKBACK)).thenReturn(Optional.of(List.of(
                suspension(UserStatus.PENDING_VERIFICATION, "SUCCESS"), suspension(UserStatus.ACTIVE, "SUCCESS"))));

        assertApiError(catchThrowable(() -> service.reactivate(ACTOR, Role.ADMIN, TARGET, NOTE)),
                HttpStatus.CONFLICT, "USER_STATUS_NOT_CHANGEABLE");
        verify(directory, never()).changeStatus(anyString(), any());
        verify(audit, never()).begin(any());
    }

    @Test
    @DisplayName("reactivate with emailVerified=false but suspended from ACTIVE (verification off in dev) -> allowed; "
            + "a FAILED pending-status record does not count")
    void reactivateDevAccountWithoutVerifiedEmail() {
        when(directory.findById(TARGET)).thenReturn(Optional.of(new UserAccount(TARGET, "Kamal Silva", EMAIL,
                "+94771112223", Role.CUSTOMER, UserStatus.SUSPENDED, false, Instant.parse("2026-01-01T00:00:00Z"))));
        when(audit.recentForTarget(TARGET, AdminUserAccountService.AUDIT_LOOKBACK)).thenReturn(Optional.of(List.of(
                suspension(UserStatus.PENDING_VERIFICATION, "FAILED"), suspension(UserStatus.ACTIVE, "SUCCESS"))));
        changeReturns(Role.CUSTOMER, UserStatus.ACTIVE);

        assertThat(service.reactivate(ACTOR, Role.ADMIN, TARGET, NOTE).status()).isEqualTo(UserStatus.ACTIVE);
        verify(ticket).succeeded();
    }

    @Test
    @DisplayName("suspending a DEACTIVATED (self-closed) account -> 409")
    void suspendDeactivated() {
        target(Role.CUSTOMER, UserStatus.DEACTIVATED);

        assertApiError(catchThrowable(() -> suspend(CODE, NOTE)), HttpStatus.CONFLICT, "USER_STATUS_NOT_CHANGEABLE");
    }

    @Test
    @DisplayName("the auth module rejects the change -> audit FAILED with its code, the error passes through unchanged")
    void authRejects() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        ApiException rejected = new ApiException(HttpStatus.FORBIDDEN, "ROLE_HIERARCHY_VIOLATION", "no");
        when(directory.changeStatus(TARGET, UserStatus.SUSPENDED)).thenThrow(rejected);

        assertThat(catchThrowable(() -> suspend(CODE, NOTE))).isSameAs(rejected);
        verify(ticket).failed("ROLE_HIERARCHY_VIOLATION");
        verify(ticket, never()).succeeded();
    }

    @Test
    @DisplayName("the PENDING audit record cannot be written -> the change does not run")
    void auditInsertFails() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        when(audit.begin(any())).thenThrow(new IllegalStateException("audit down"));

        assertThat(catchThrowable(() -> suspend(CODE, NOTE))).isInstanceOf(IllegalStateException.class);
        verify(directory, never()).changeStatus(anyString(), any());
    }

    @Test
    @DisplayName("logs contain ids and statuses only: never the email, phone or note")
    void logsHaveNoPersonalData() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        changeReturns(Role.PROVIDER, UserStatus.SUSPENDED);

        suspend(CODE, NOTE);

        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).contains(TARGET)
                    .doesNotContain(EMAIL).doesNotContain("+9477").doesNotContain(NOTE);
        }
    }
}

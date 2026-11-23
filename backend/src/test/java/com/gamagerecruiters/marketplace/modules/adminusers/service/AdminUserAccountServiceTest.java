package com.gamagerecruiters.marketplace.modules.adminusers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.adminusers.domain.SuspensionReason;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AccountActionResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.port.AccountActionAuditPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.ProviderListingPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort;
import com.gamagerecruiters.marketplace.modules.adminusers.port.UserDirectoryPort.UserAccount;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.dto.Granularity;

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
    private ProviderListingPort listings;
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
        listings = mock(ProviderListingPort.class);
        service = new AdminUserAccountService(directory, audit, listings);
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
        when(directory.changeStatus(eq(ACTOR), eq(TARGET), any(), eq(status))).thenReturn(new UserAccount(TARGET, "Kamal Silva", EMAIL,
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
        verify(directory).changeStatus(ACTOR, TARGET, UserStatus.ACTIVE, UserStatus.SUSPENDED);
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

        verify(directory, never()).changeStatus(anyString(), anyString(), any(), any());
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
        verify(directory, never()).changeStatus(anyString(), anyString(), any(), any());
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
        verify(directory, never()).changeStatus(anyString(), anyString(), any(), any());
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
                CODE, outcome, Instant.parse("2026-11-01T00:00:00Z"), null);
    }

    @Test
    @DisplayName("reactivate an account that was suspended while PENDING_VERIFICATION -> 409, nothing changed")
    void reactivateSuspendedWhilePending() {
        target(Role.CUSTOMER, UserStatus.SUSPENDED);
        when(audit.recentForTarget(TARGET, AdminUserAccountService.AUDIT_LOOKBACK)).thenReturn(Optional.of(List.of(
                suspension(UserStatus.PENDING_VERIFICATION, "SUCCESS"), suspension(UserStatus.ACTIVE, "SUCCESS"))));

        assertApiError(catchThrowable(() -> service.reactivate(ACTOR, Role.ADMIN, TARGET, NOTE)),
                HttpStatus.CONFLICT, "USER_STATUS_NOT_CHANGEABLE");
        verify(directory, never()).changeStatus(anyString(), anyString(), any(), any());
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
        when(directory.changeStatus(ACTOR, TARGET, UserStatus.ACTIVE, UserStatus.SUSPENDED)).thenThrow(rejected);

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
        verify(directory, never()).changeStatus(anyString(), anyString(), any(), any());
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

    // ---------- provider listing visibility (agreed with WP03) ----------

    @Test
    @DisplayName("provider suspend: listings hidden (true) only AFTER the status change and its SUCCESS record")
    void providerSuspendHidesListingsAfterStatusChange() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        changeReturns(Role.PROVIDER, UserStatus.SUSPENDED);

        AccountActionResponse result = suspend(CODE, NOTE);

        InOrder order = inOrder(directory, ticket, listings);
        order.verify(directory).changeStatus(ACTOR, TARGET, UserStatus.ACTIVE, UserStatus.SUSPENDED);
        order.verify(ticket).succeeded();
        order.verify(listings).updateSuspensionVisibility(TARGET, true);
        assertThat(result.listingVisibilitySynced()).isTrue();
        verify(ticket, never()).listingVisibilityFailed();
    }

    @Test
    @DisplayName("provider reactivate: listings restored (false) only after the user is saved ACTIVE")
    void providerReactivateRestoresListingsAfterActive() {
        target(Role.PROVIDER, UserStatus.SUSPENDED);
        changeReturns(Role.PROVIDER, UserStatus.ACTIVE);

        AccountActionResponse result = service.reactivate(ACTOR, Role.ADMIN, TARGET, NOTE);

        InOrder order = inOrder(directory, listings);
        order.verify(directory).changeStatus(ACTOR, TARGET, UserStatus.SUSPENDED, UserStatus.ACTIVE);
        order.verify(listings).updateSuspensionVisibility(TARGET, false);
        assertThat(result.listingVisibilitySynced()).isTrue();
    }

    @Test
    @DisplayName("customer suspend / reactivate: the listing module is never called, no listingVisibilitySynced")
    void customerNeverTouchesListings() {
        target(Role.CUSTOMER, UserStatus.ACTIVE);
        changeReturns(Role.CUSTOMER, UserStatus.SUSPENDED);
        assertThat(suspend(CODE, NOTE).listingVisibilitySynced()).isNull();

        target(Role.CUSTOMER, UserStatus.SUSPENDED);
        changeReturns(Role.CUSTOMER, UserStatus.ACTIVE);
        service.reactivate(ACTOR, Role.ADMIN, TARGET, NOTE);

        verifyNoInteractions(listings);
    }

    @Test
    @DisplayName("status change fails (409 / auth error) or is a no-op: the listing module is never called")
    void failedOrIdempotentChangeNeverTouchesListings() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        when(directory.changeStatus(ACTOR, TARGET, UserStatus.ACTIVE, UserStatus.SUSPENDED)).thenThrow(
                new ResourceConflictException(UserDirectoryPort.CONCURRENT_MODIFICATION, "changed"));
        assertThat(catchThrowable(() -> suspend(CODE, NOTE))).isInstanceOf(ResourceConflictException.class);

        target(Role.PROVIDER, UserStatus.SUSPENDED);
        assertThat(suspend(CODE, NOTE).changed()).isFalse();

        verifyNoInteractions(listings);
    }

    @Test
    @DisplayName("listing update throws: status change kept (no rollback, SUCCESS), audit marked "
            + "listingVisibilitySync FAILED, response listingVisibilitySynced=false, log has ids only")
    void listingFailureKeepsStatus() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        changeReturns(Role.PROVIDER, UserStatus.SUSPENDED);
        doThrow(new IllegalStateException("listing db at host-123 down")).when(listings)
                .updateSuspensionVisibility(TARGET, true);

        AccountActionResponse result = suspend(CODE, NOTE);

        assertThat(result.changed()).isTrue();
        assertThat(result.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(result.listingVisibilitySynced()).isFalse();
        verify(ticket).succeeded();
        verify(ticket).listingVisibilityFailed();
        verify(ticket, never()).failed(anyString());
        verify(directory, never()).changeStatus(ACTOR, TARGET, UserStatus.SUSPENDED, UserStatus.ACTIVE);
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("ADMIN_USER_LISTING_VISIBILITY_FAILED").contains(TARGET)
                .contains("IllegalStateException").doesNotContain("host-123"));
    }

    @Test
    @DisplayName("sync endpoint: re-applies the value for the CURRENT status and clears the FAILED markers; idempotent")
    void syncReappliesCurrentStatus() {
        target(Role.PROVIDER, UserStatus.SUSPENDED);
        AccountActionResponse first = service.syncListingVisibility(ACTOR, TARGET);
        AccountActionResponse second = service.syncListingVisibility(ACTOR, TARGET);

        assertThat(first.listingVisibilitySynced()).isTrue();
        assertThat(first.changed()).isFalse();
        assertThat(second).isEqualTo(first);
        verify(listings, org.mockito.Mockito.times(2)).updateSuspensionVisibility(TARGET, true);
        verify(audit, org.mockito.Mockito.times(2)).listingVisibilityResynced(TARGET);

        target(Role.PROVIDER, UserStatus.ACTIVE);
        service.syncListingVisibility(ACTOR, TARGET);
        verify(listings).updateSuspensionVisibility(TARGET, false);
        verify(directory, never()).changeStatus(anyString(), anyString(), any(), any());
        verify(audit, never()).begin(any());
    }

    @Test
    @DisplayName("sync after a failed listing update fixes it: the earlier failure, then the retry succeeds")
    void syncFixesEarlierFailure() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        changeReturns(Role.PROVIDER, UserStatus.SUSPENDED);
        doThrow(new IllegalStateException("down")).doNothing().when(listings).updateSuspensionVisibility(TARGET, true);
        assertThat(suspend(CODE, NOTE).listingVisibilitySynced()).isFalse();

        target(Role.PROVIDER, UserStatus.SUSPENDED);
        assertThat(service.syncListingVisibility(ACTOR, TARGET).listingVisibilitySynced()).isTrue();
        verify(audit).listingVisibilityResynced(TARGET);
    }

    @Test
    @DisplayName("sync: listing module still failing -> 503 LISTING_VISIBILITY_SYNC_FAILED, markers kept; customer "
            + "-> 403; unknown -> 404")
    void syncErrors() {
        target(Role.PROVIDER, UserStatus.SUSPENDED);
        doThrow(new IllegalStateException("down")).when(listings).updateSuspensionVisibility(TARGET, true);
        assertApiError(catchThrowable(() -> service.syncListingVisibility(ACTOR, TARGET)),
                HttpStatus.SERVICE_UNAVAILABLE, "LISTING_VISIBILITY_SYNC_FAILED");
        verify(audit, never()).listingVisibilityResynced(anyString());

        target(Role.CUSTOMER, UserStatus.SUSPENDED);
        assertApiError(catchThrowable(() -> service.syncListingVisibility(ACTOR, TARGET)),
                HttpStatus.FORBIDDEN, "USER_ACTION_NOT_ALLOWED");

        when(directory.findById("missing")).thenReturn(Optional.empty());
        assertApiError(catchThrowable(() -> service.syncListingVisibility(ACTOR, "missing")),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    // ---------- concurrent status changes (WP01 atomic status-change contract) ----------

    @Test
    @DisplayName("reactivate passes the actor and the status it checked (SUSPENDED) as the expected status")
    void reactivatePassesExpectedStatus() {
        target(Role.CUSTOMER, UserStatus.SUSPENDED);
        changeReturns(Role.CUSTOMER, UserStatus.ACTIVE);

        service.reactivate(ACTOR, Role.ADMIN, TARGET, NOTE);

        verify(directory).changeStatus(ACTOR, TARGET, UserStatus.SUSPENDED, UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("the status changed after it was read -> audit FAILED with CONCURRENT_MODIFICATION, 409 passes "
            + "through, no success logged")
    void concurrentModification() {
        target(Role.PROVIDER, UserStatus.ACTIVE);
        ResourceConflictException conflict = new ResourceConflictException(UserDirectoryPort.CONCURRENT_MODIFICATION,
                "changed");
        when(directory.changeStatus(ACTOR, TARGET, UserStatus.ACTIVE, UserStatus.SUSPENDED)).thenThrow(conflict);

        assertThat(catchThrowable(() -> suspend(CODE, NOTE))).isSameAs(conflict);
        verify(ticket).failed(UserDirectoryPort.CONCURRENT_MODIFICATION);
        verify(ticket, never()).succeeded();
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("ADMIN_USER_STATUS_CHANGED"));
    }

    @Test
    @DisplayName("two admins suspend the same account at the same moment -> exactly one succeeds, the other gets 409 "
            + "CONCURRENT_MODIFICATION; audit has one SUCCESS and one FAILED record")
    void twoAdminsRace() throws Exception {
        AtomicDirectory atomicDirectory = new AtomicDirectory(UserStatus.ACTIVE);
        // Both requests read ACTIVE and write their PENDING record before either change is applied.
        RacingAudit racingAudit = new RacingAudit(new CyclicBarrier(2));
        AdminUserAccountService racing = new AdminUserAccountService(atomicDirectory, racingAudit,
                mock(ProviderListingPort.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AccountActionResponse> first = pool.submit(
                    () -> racing.suspend("admin-a", Role.ADMIN, TARGET, CODE, NOTE));
            Future<AccountActionResponse> second = pool.submit(
                    () -> racing.suspend("admin-b", Role.ADMIN, TARGET, "POLICY_VIOLATION", null));

            List<Object> results = new ArrayList<>();
            for (Future<AccountActionResponse> f : List.of(first, second)) {
                try {
                    results.add(f.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    results.add(e.getCause());
                }
            }

            assertThat(results).filteredOn(AccountActionResponse.class::isInstance).hasSize(1)
                    .allSatisfy(r -> assertThat(((AccountActionResponse) r).changed()).isTrue());
            assertThat(results).filteredOn(Throwable.class::isInstance).singleElement()
                    .satisfies(e -> assertApiError((Throwable) e, HttpStatus.CONFLICT,
                            UserDirectoryPort.CONCURRENT_MODIFICATION));
            assertThat(racingAudit.outcomes).containsExactlyInAnyOrder("SUCCESS",
                    "FAILED:" + UserDirectoryPort.CONCURRENT_MODIFICATION);
            assertThat(atomicDirectory.status.get()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(atomicDirectory.applied.get()).as("the status was written once").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** In-memory account with the auth module's contract: the change applies only while the status is the expected one. */
    private static final class AtomicDirectory implements UserDirectoryPort {
        final AtomicReference<UserStatus> status;
        final AtomicInteger applied = new AtomicInteger();

        AtomicDirectory(UserStatus initial) {
            status = new AtomicReference<>(initial);
        }

        private UserAccount account(UserStatus s) {
            return new UserAccount(TARGET, "Kamal Silva", EMAIL, "+94771112223", Role.PROVIDER, s, true,
                    Instant.parse("2026-01-01T00:00:00Z"));
        }

        @Override
        public Optional<UserAccount> findById(String userId) {
            return Optional.of(account(status.get()));
        }

        @Override
        public boolean supportsAccountActions() {
            return true;
        }

        @Override
        public UserAccount changeStatus(String actorId, String userId, UserStatus expectedStatus, UserStatus newStatus) {
            if (!status.compareAndSet(expectedStatus, newStatus)) {
                throw new ResourceConflictException(CONCURRENT_MODIFICATION, "changed");
            }
            applied.incrementAndGet();
            return account(newStatus);
        }

        @Override
        public Page<UserAccount> search(String actorId, Set<Role> roles, UserStatus s, String q, Pageable pageable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count(String actorId, Role role, UserStatus s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Long> joinedBetween(String actorId, Role role, Instant from, Instant to) {
            return Optional.empty();
        }

        @Override
        public Optional<List<RegistrationCount>> registrations(String actorId, Granularity granularity,
                                                               List<Bucket> buckets) {
            return Optional.empty();
        }
    }

    /** Audit whose begin() waits until both requests have written their PENDING record. */
    private static final class RacingAudit implements AccountActionAuditPort {
        final Queue<String> outcomes = new ConcurrentLinkedQueue<>();
        final CyclicBarrier bothPending;

        RacingAudit(CyclicBarrier bothPending) {
            this.bothPending = bothPending;
        }

        @Override
        public Ticket begin(StatusChange change) {
            try {
                bothPending.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new Ticket() {
                @Override
                public String auditLogId() {
                    return "audit-" + change.actorId();
                }

                @Override
                public void succeeded() {
                    outcomes.add("SUCCESS");
                }

                @Override
                public void failed(String failureCode) {
                    outcomes.add("FAILED:" + failureCode);
                }

                @Override
                public void listingVisibilityFailed() {
                    outcomes.add("LISTING_VISIBILITY_FAILED");
                }
            };
        }

        @Override
        public Optional<List<AdminAction>> recentForTarget(String targetUserId, int limit) {
            return Optional.of(List.of());
        }

        @Override
        public void listingVisibilityResynced(String targetUserId) {
        }
    }
}

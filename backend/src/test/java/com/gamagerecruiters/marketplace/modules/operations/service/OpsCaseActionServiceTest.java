package com.gamagerecruiters.marketplace.modules.operations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.adminusers.dto.AccountActionResponse;
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUserAccountService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActionType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsActionResult;

/** Role rules, note validation, the SUSPEND flow, and F3 partial-success recovery for recording operations case actions (WP09). */
@ExtendWith(MockitoExtension.class)
class OpsCaseActionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T04:30:00Z");
    private static final String USER = "user-1";
    private static final String ADMIN_ID = "admin-1";
    private static final String NOTE = "Confirmed repeated late cancellations across three bookings.";

    @Mock
    private OpsCaseActionStore store;
    @Mock
    private OpsCaseEligibilityService eligibility;
    @Mock
    private AdminUserAccountService adminUserAccountService;

    private OpsCaseActionService service;

    @BeforeEach
    void setUp() {
        service = new OpsCaseActionService(store, eligibility, adminUserAccountService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("SUPPORT_ADMIN cannot SUSPEND: 403, no suspend flow, nothing recorded")
    void supportCannotSuspend() {
        ApiException ex = catchThrowableOfType(() -> service.record(OpsCaseType.REPEAT_CANCELLATION, USER,
                "SUSPEND", NOTE, ADMIN_ID, Role.SUPPORT_ADMIN), ApiException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getErrorCode()).isEqualTo("OPS_SUPPORT_CANNOT_PERFORM_ACTION");
        verifyNoInteractions(adminUserAccountService);
        verify(store, never()).insert(any());
    }

    @Test
    @DisplayName("ADMIN SUSPEND calls the Admin Users suspend flow and records SUSPENDED")
    void adminSuspendCallsFlow() {
        when(adminUserAccountService.suspend(eq(ADMIN_ID), eq(Role.ADMIN), eq(USER), any(), any()))
                .thenReturn(new AccountActionResponse(USER, Role.CUSTOMER, UserStatus.ACTIVE,
                        UserStatus.SUSPENDED, true, "audit-1", true));

        OpsActionResult result = service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "SUSPEND", NOTE, ADMIN_ID,
                Role.ADMIN);

        verify(adminUserAccountService).suspend(ADMIN_ID, Role.ADMIN, USER,
                OpsCaseActionService.SUSPEND_REASON_CODE, NOTE);
        assertThat(result.caseStatus()).isEqualTo(OpsCaseStatus.SUSPENDED);
        assertThat(result.suspensionApplied()).isTrue();
        assertThat(result.listingVisibilitySynced()).isTrue();
        assertThat(result.opsRecordPending()).isFalse();
    }

    @Test
    @DisplayName("SUPPORT_ADMIN RECOMMEND_SUSPENSION is recorded only (no suspend flow)")
    void supportRecommendRecordedOnly() {
        OpsActionResult result = service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "RECOMMEND_SUSPENSION", NOTE,
                ADMIN_ID, Role.SUPPORT_ADMIN);

        verifyNoInteractions(adminUserAccountService);
        assertThat(result.action()).isEqualTo(OpsActionType.RECOMMEND_SUSPENSION);
        assertThat(result.caseStatus()).isEqualTo(OpsCaseStatus.SUSPENSION_RECOMMENDED);
        assertThat(result.suspensionApplied()).isFalse();
    }

    @Test
    @DisplayName("SUSPEND / RECOMMEND_SUSPENSION do not apply to overdue bookings: 400")
    void actionNotAllowedForCase() {
        ApiException ex = catchThrowableOfType(() -> service.record(OpsCaseType.OVERDUE_BOOKING, "booking-1",
                "RECOMMEND_SUSPENSION", NOTE, ADMIN_ID, Role.ADMIN), ApiException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("OPS_ACTION_NOT_ALLOWED_FOR_CASE");
        verify(store, never()).insert(any());
    }

    @Test
    @DisplayName("BAN is not a valid action (no ban exists): 400 OPS_INVALID_ACTION")
    void noBanAction() {
        ApiException ex = catchThrowableOfType(() -> service.record(OpsCaseType.REPEAT_CANCELLATION, USER,
                "BAN", NOTE, ADMIN_ID, Role.ADMIN), ApiException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("OPS_INVALID_ACTION");
        verifyNoInteractions(adminUserAccountService);
    }

    @Test
    @DisplayName("A too-short or too-long note is rejected (400)")
    void noteValidation() {
        ApiException tooShort = catchThrowableOfType(() -> service.record(OpsCaseType.OVERDUE_BOOKING, "b1",
                "CONTACT_PROVIDER", "short", ADMIN_ID, Role.SUPPORT_ADMIN), ApiException.class);
        assertThat(tooShort.getErrorCode()).isEqualTo("OPS_INVALID_NOTE");

        String tooLong = "x".repeat(1001);
        ApiException over = catchThrowableOfType(() -> service.record(OpsCaseType.OVERDUE_BOOKING, "b1",
                "CONTACT_PROVIDER", tooLong, ADMIN_ID, Role.SUPPORT_ADMIN), ApiException.class);
        assertThat(over.getErrorCode()).isEqualTo("OPS_INVALID_NOTE");
    }

    @Test
    @DisplayName("F5: SUSPEND enforces max 500 characters, while record-only actions permit 501–1000 characters")
    void suspendEnforces500CharNoteLimit() {
        String note501 = "x".repeat(501);

        // SUSPEND rejects 501 characters before calling Admin Users suspend flow
        ApiException suspendEx = catchThrowableOfType(() -> service.record(OpsCaseType.REPEAT_CANCELLATION, USER,
                "SUSPEND", note501, ADMIN_ID, Role.ADMIN), ApiException.class);
        assertThat(suspendEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(suspendEx.getErrorCode()).isEqualTo("OPS_INVALID_NOTE");
        assertThat(suspendEx.getMessage()).contains("500");
        verifyNoInteractions(adminUserAccountService);

        // Record-only action allows 501 characters
        OpsActionResult result = service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "RECOMMEND_SUSPENSION",
                note501, ADMIN_ID, Role.SUPPORT_ADMIN);
        assertThat(result.action()).isEqualTo(OpsActionType.RECOMMEND_SUSPENSION);
    }

    @Test
    @DisplayName("A support action maps to its resulting status and area")
    void supportActionRecorded() {
        OpsActionResult result = service.record(OpsCaseType.OVERDUE_BOOKING, "b1", "contact_provider", NOTE,
                ADMIN_ID, Role.SUPPORT_ADMIN);

        verify(store).insert(any());
        assertThat(result.action()).isEqualTo(OpsActionType.CONTACT_PROVIDER);
        assertThat(result.caseStatus()).isEqualTo(OpsCaseStatus.CONTACTED);
        assertThat(result.recordedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("F2: an ineligible/unknown case is rejected before any suspend or record")
    void ineligibleCaseRejectedBeforeMutation() {
        org.mockito.Mockito.doThrow(new ApiException(HttpStatus.NOT_FOUND, "OPS_REPEAT_CASE_NOT_FOUND", "not a case"))
                .when(eligibility).assertEligible(OpsCaseType.REPEAT_CANCELLATION, USER);

        ApiException ex = catchThrowableOfType(() -> service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "SUSPEND",
                NOTE, ADMIN_ID, Role.ADMIN), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo("OPS_REPEAT_CASE_NOT_FOUND");
        verifyNoInteractions(adminUserAccountService);
        verify(store, never()).insert(any());
    }

    @Test
    @DisplayName("F2: when the source is unavailable the action fails closed before any mutation")
    void sourceUnavailableFailsClosed() {
        org.mockito.Mockito.doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OPS_SOURCE_UNAVAILABLE", "down"))
                .when(eligibility).assertEligible(OpsCaseType.OVERDUE_BOOKING, "b1");

        ApiException ex = catchThrowableOfType(() -> service.record(OpsCaseType.OVERDUE_BOOKING, "b1",
                "CONTACT_PROVIDER", NOTE, ADMIN_ID, Role.SUPPORT_ADMIN), ApiException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(store, never()).insert(any());
    }

    @Test
    @DisplayName("F2: eligibility is checked for an eligible case, then the action is recorded")
    void eligibleCaseIsVerifiedThenRecorded() {
        service.record(OpsCaseType.OVERDUE_BOOKING, "b1", "CONTACT_PROVIDER", NOTE, ADMIN_ID, Role.SUPPORT_ADMIN);

        verify(eligibility).assertEligible(OpsCaseType.OVERDUE_BOOKING, "b1");
        verify(store).insert(any());
    }

    @Test
    @DisplayName("The suspend flow's errors (e.g. 409) propagate and nothing extra is recorded")
    void suspendFlowErrorPropagates() {
        when(adminUserAccountService.suspend(eq(ADMIN_ID), eq(Role.ADMIN), eq(USER), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "USER_STATUS_NOT_CHANGEABLE", "already suspended"));

        ApiException ex = catchThrowableOfType(() -> service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "SUSPEND",
                NOTE, ADMIN_ID, Role.ADMIN), ApiException.class);

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verify(store, never()).insert(any());
    }

    // ---- F3: Partial-success and recovery tests ----

    @Test
    @DisplayName("F3: suspension succeeds, ops log fails → account stays SUSPENDED; partial success returned")
    void suspensionSucceedsOpsLogFails_partialSuccess() {
        when(adminUserAccountService.suspend(eq(ADMIN_ID), eq(Role.ADMIN), eq(USER), any(), any()))
                .thenReturn(new AccountActionResponse(USER, Role.CUSTOMER, UserStatus.ACTIVE,
                        UserStatus.SUSPENDED, true, "audit-1", true));
        when(store.insert(any())).thenThrow(new RuntimeException("MongoDB timeout"));

        OpsActionResult result = service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "SUSPEND", NOTE, ADMIN_ID,
                Role.ADMIN);

        // The account's true status is preserved — suspension was applied and not rolled back.
        assertThat(result.suspensionApplied()).isTrue();
        // The partial outcome is NOT misrepresented as a no-op.
        assertThat(result.caseStatus()).isEqualTo(OpsCaseStatus.SUSPENDED);
        assertThat(result.opsRecordPending()).isTrue();
        // Listing visibility sync status is propagated from the authoritative suspension response.
        assertThat(result.listingVisibilitySynced()).isTrue();
    }

    @Test
    @DisplayName("F3: suspension succeeds, first insert fails, retry succeeds → opsRecordPending is false")
    void suspensionSucceedsOpsLogFailsThenRetrySucceeds() {
        when(adminUserAccountService.suspend(eq(ADMIN_ID), eq(Role.ADMIN), eq(USER), any(), any()))
                .thenReturn(new AccountActionResponse(USER, Role.CUSTOMER, UserStatus.ACTIVE,
                        UserStatus.SUSPENDED, true, "audit-1", true));
        when(store.insert(any()))
                .thenThrow(new RuntimeException("first attempt fails"))
                .thenAnswer(inv -> inv.getArgument(0)); // retry succeeds

        OpsActionResult result = service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "SUSPEND", NOTE, ADMIN_ID,
                Role.ADMIN);

        assertThat(result.suspensionApplied()).isTrue();
        assertThat(result.opsRecordPending()).isFalse();
        assertThat(result.caseStatus()).isEqualTo(OpsCaseStatus.SUSPENDED);
    }

    @Test
    @DisplayName("F3: non-suspend action ops log failure propagates (no side-effect to reconcile)")
    void nonSuspendOpsLogFailurePropagates() {
        when(store.insert(any())).thenThrow(new RuntimeException("MongoDB down"));

        RuntimeException ex = catchThrowableOfType(() -> service.record(OpsCaseType.OVERDUE_BOOKING, "b1",
                "CONTACT_PROVIDER", NOTE, ADMIN_ID, Role.SUPPORT_ADMIN), RuntimeException.class);

        assertThat(ex.getMessage()).isEqualTo("MongoDB down");
        verifyNoInteractions(adminUserAccountService);
    }

    @Test
    @DisplayName("F3: listing-visibility sync failure is faithfully propagated (not hidden as fully synced)")
    void listingVisibilitySyncFailurePropagated() {
        when(adminUserAccountService.suspend(eq(ADMIN_ID), eq(Role.ADMIN), eq(USER), any(), any()))
                .thenReturn(new AccountActionResponse(USER, Role.PROVIDER, UserStatus.ACTIVE,
                        UserStatus.SUSPENDED, true, "audit-1", false)); // listings NOT synced

        OpsActionResult result = service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "SUSPEND", NOTE, ADMIN_ID,
                Role.ADMIN);

        assertThat(result.suspensionApplied()).isTrue();
        assertThat(result.listingVisibilitySynced()).isFalse();
        assertThat(result.opsRecordPending()).isFalse();
    }

    @Test
    @DisplayName("F3: idempotent suspend (account already SUSPENDED) → changed=false, ops log still recorded")
    void idempotentSuspendAlreadySuspended() {
        when(adminUserAccountService.suspend(eq(ADMIN_ID), eq(Role.ADMIN), eq(USER), any(), any()))
                .thenReturn(new AccountActionResponse(USER, Role.CUSTOMER, UserStatus.SUSPENDED,
                        UserStatus.SUSPENDED, false, null)); // already suspended, changed=false

        OpsActionResult result = service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "SUSPEND", NOTE, ADMIN_ID,
                Role.ADMIN);

        // suspensionApplied reflects changed=false (no new suspension applied)
        assertThat(result.suspensionApplied()).isFalse();
        // But the ops record is still created for audit purposes
        verify(store).insert(any());
        assertThat(result.opsRecordPending()).isFalse();
    }

    @Test
    @DisplayName("F3: retrying after partial success does not create duplicate decisions")
    void retryAfterPartialSuccessNoDuplicates() {
        // First call: suspend succeeds, ops log permanently fails → partial success
        when(adminUserAccountService.suspend(eq(ADMIN_ID), eq(Role.ADMIN), eq(USER), any(), any()))
                .thenReturn(new AccountActionResponse(USER, Role.CUSTOMER, UserStatus.ACTIVE,
                        UserStatus.SUSPENDED, true, "audit-1", true));
        when(store.insert(any())).thenThrow(new RuntimeException("DB down"));

        OpsActionResult partial = service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "SUSPEND", NOTE, ADMIN_ID,
                Role.ADMIN);
        assertThat(partial.opsRecordPending()).isTrue();

        // Retry: suspend returns idempotent (already suspended), ops log now succeeds
        when(adminUserAccountService.suspend(eq(ADMIN_ID), eq(Role.ADMIN), eq(USER), any(), any()))
                .thenReturn(new AccountActionResponse(USER, Role.CUSTOMER, UserStatus.SUSPENDED,
                        UserStatus.SUSPENDED, false, null));
        org.mockito.Mockito.doAnswer(inv -> inv.getArgument(0)).when(store).insert(any());

        OpsActionResult retry = service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "SUSPEND", NOTE, ADMIN_ID,
                Role.ADMIN);

        // Retry did not create a duplicate suspension (changed=false), and ops log succeeded.
        assertThat(retry.suspensionApplied()).isFalse();
        assertThat(retry.opsRecordPending()).isFalse();
        assertThat(retry.caseStatus()).isEqualTo(OpsCaseStatus.SUSPENDED);
    }

    @Test
    @DisplayName("F3: retrying an already-suspended account with continued ops log failure still safely returns partial success")
    void retryAlreadySuspendedWithContinuedOpsFailureReturnsPartialSuccess() {
        when(adminUserAccountService.suspend(eq(ADMIN_ID), eq(Role.ADMIN), eq(USER), any(), any()))
                .thenReturn(new AccountActionResponse(USER, Role.CUSTOMER, UserStatus.SUSPENDED,
                        UserStatus.SUSPENDED, false, null));
        when(store.insert(any())).thenThrow(new RuntimeException("DB still down"));

        OpsActionResult retry = service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "SUSPEND", NOTE, ADMIN_ID,
                Role.ADMIN);

        assertThat(retry.opsRecordPending()).isTrue();
        assertThat(retry.suspensionApplied()).isTrue();
        assertThat(retry.caseStatus()).isEqualTo(OpsCaseStatus.SUSPENDED);
    }
}

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
import com.gamagerecruiters.marketplace.modules.adminusers.service.AdminUserAccountService;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsActionType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;
import com.gamagerecruiters.marketplace.modules.operations.dto.OpsActionResult;

/** Role rules, note validation, and the SUSPEND flow for recording operations case actions (WP09). */
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
        OpsActionResult result = service.record(OpsCaseType.REPEAT_CANCELLATION, USER, "SUSPEND", NOTE, ADMIN_ID,
                Role.ADMIN);

        verify(adminUserAccountService).suspend(ADMIN_ID, Role.ADMIN, USER,
                OpsCaseActionService.SUSPEND_REASON_CODE, NOTE);
        assertThat(result.caseStatus()).isEqualTo(OpsCaseStatus.SUSPENDED);
        assertThat(result.suspensionApplied()).isTrue();
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
}

package com.gamagerecruiters.marketplace.modules.operations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;
import com.gamagerecruiters.marketplace.modules.operations.port.LateStartsPort;
import com.gamagerecruiters.marketplace.modules.operations.port.OverdueBookingsPort;
import com.gamagerecruiters.marketplace.modules.operations.port.RepeatCancellationsPort;

/** Validates case eligibility against the authoritative sources, failing closed when a source is unavailable (F2). */
@ExtendWith(MockitoExtension.class)
class OpsCaseEligibilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T04:00:00Z");

    @Mock
    private OverdueBookingsPort overduePort;
    @Mock
    private LateStartsPort lateStartsPort;
    @Mock
    private RepeatCancellationsPort repeatPort;

    private OpsCaseEligibilityService service;

    @BeforeEach
    void setUp() {
        service = new OpsCaseEligibilityService(overduePort, lateStartsPort, repeatPort, 4, 30);
    }

    private static OverdueBookingsPort.OverdueBooking overdue(String id) {
        return new OverdueBookingsPort.OverdueBooking(id, "REF-" + id, "Svc", "c", "p", NOW, NOW);
    }

    private static RepeatCancellationsPort.RepeatCanceller canceller(String id) {
        return new RepeatCancellationsPort.RepeatCanceller(id, "CUSTOMER", 5, NOW);
    }

    // ---- Overdue ----

    @Test
    @DisplayName("overdue: an id in the live overdue set is eligible")
    void overdueEligible() {
        when(overduePort.findOverdue(anyInt())).thenReturn(OverdueBookingsPort.Result.available(List.of(overdue("b1"))));
        assertThatCode(() -> service.assertEligible(OpsCaseType.OVERDUE_BOOKING, "b1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("overdue: an unknown id is rejected 404")
    void overdueUnknown() {
        when(overduePort.findOverdue(anyInt())).thenReturn(OverdueBookingsPort.Result.available(List.of(overdue("b1"))));
        ApiException ex = catchThrowableOfType(
                () -> service.assertEligible(OpsCaseType.OVERDUE_BOOKING, "ghost"), ApiException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("OPS_OVERDUE_CASE_NOT_FOUND");
    }

    @Test
    @DisplayName("overdue: an unavailable source fails closed 503")
    void overdueSourceUnavailable() {
        when(overduePort.findOverdue(anyInt())).thenReturn(OverdueBookingsPort.Result.unavailable("WP05 down"));
        ApiException ex = catchThrowableOfType(
                () -> service.assertEligible(OpsCaseType.OVERDUE_BOOKING, "b1"), ApiException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ex.getErrorCode()).isEqualTo("OPS_SOURCE_UNAVAILABLE");
    }

    // ---- Late cases ----

    @Test
    @DisplayName("late case: rejected 503 while late-start tracking is unavailable (stub)")
    void lateCaseUnavailable() {
        when(lateStartsPort.findLateStarts(anyInt())).thenReturn(LateStartsPort.Result.unavailable("stub"));
        ApiException ex = catchThrowableOfType(
                () -> service.assertEligible(OpsCaseType.LATE_CASE, "b1"), ApiException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ex.getErrorCode()).isEqualTo("OPS_SOURCE_UNAVAILABLE");
    }

    // ---- Repeat cancellations ----

    @Test
    @DisplayName("repeat: a flagged user is eligible; the configured threshold is used")
    void repeatEligible() {
        when(repeatPort.findRepeatCancellations(4, 30))
                .thenReturn(RepeatCancellationsPort.Result.available(List.of(canceller("u1"))));
        assertThatCode(() -> service.assertEligible(OpsCaseType.REPEAT_CANCELLATION, "u1")).doesNotThrowAnyException();
        verify(repeatPort).findRepeatCancellations(4, 30);
    }

    @Test
    @DisplayName("repeat: an account below the threshold is rejected 404 (guards SUSPEND)")
    void repeatNotFlagged() {
        when(repeatPort.findRepeatCancellations(4, 30))
                .thenReturn(RepeatCancellationsPort.Result.available(List.of(canceller("u1"))));
        ApiException ex = catchThrowableOfType(
                () -> service.assertEligible(OpsCaseType.REPEAT_CANCELLATION, "not-flagged"), ApiException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("OPS_REPEAT_CASE_NOT_FOUND");
    }

    @Test
    @DisplayName("repeat: an unavailable source fails closed 503")
    void repeatSourceUnavailable() {
        when(repeatPort.findRepeatCancellations(4, 30))
                .thenReturn(RepeatCancellationsPort.Result.unavailable("WP05 down"));
        ApiException ex = catchThrowableOfType(
                () -> service.assertEligible(OpsCaseType.REPEAT_CANCELLATION, "u1"), ApiException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}

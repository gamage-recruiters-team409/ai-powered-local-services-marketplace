package com.gamagerecruiters.marketplace.modules.operations.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseType;
import com.gamagerecruiters.marketplace.modules.operations.domain.OperationsRules;
import com.gamagerecruiters.marketplace.modules.operations.port.LateStartsPort;
import com.gamagerecruiters.marketplace.modules.operations.port.OverdueBookingsPort;
import com.gamagerecruiters.marketplace.modules.operations.port.RepeatCancellationsPort;

/**
 * Validates that a case action targets a real, eligible case of the given type against the authoritative source
 * (finding F2), before any action is recorded or any account is suspended:
 * <ul>
 *   <li><b>OVERDUE_BOOKING</b> — the booking must be in the live overdue set (WP05 {@code findOverdueBookings}).</li>
 *   <li><b>LATE_CASE</b> — the late-start source must be available and list the booking; while late-start tracking is
 *       unavailable (WP05 stub) no late-case action can be verified, so it is rejected.</li>
 *   <li><b>REPEAT_CANCELLATION</b> — the user must meet the configured cancellation threshold (WP05
 *       {@code findRepeatCancellations}); this guards SUSPEND in particular.</li>
 * </ul>
 *
 * <p><b>Fail closed:</b> if the authoritative source is unavailable the action is rejected (503) before any mutation,
 * never recorded optimistically. The eligibility set is the full authoritative fetch (not the UI's first page).</p>
 */
@Service
public class OpsCaseEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(OpsCaseEligibilityService.class);

    private final OverdueBookingsPort overduePort;
    private final LateStartsPort lateStartsPort;
    private final RepeatCancellationsPort repeatPort;
    private final int minCount;
    private final int days;

    public OpsCaseEligibilityService(OverdueBookingsPort overduePort, LateStartsPort lateStartsPort,
                                     RepeatCancellationsPort repeatPort,
                                     @Value("${app.operations.repeat-cancellation.min-count:4}") int minCount,
                                     @Value("${app.operations.repeat-cancellation.days:30}") int days) {
        this.overduePort = overduePort;
        this.lateStartsPort = lateStartsPort;
        this.repeatPort = repeatPort;
        this.minCount = minCount < 1 ? 4 : minCount;
        this.days = days < 1 ? 30 : days;
    }

    /** Throws 503 when the source is unavailable, or 404 when the subject is not an eligible case; returns on success. */
    public void assertEligible(OpsCaseType caseType, String subjectId) {
        switch (caseType) {
            case OVERDUE_BOOKING -> assertOverdue(subjectId);
            case LATE_CASE -> assertLateCase(subjectId);
            case REPEAT_CANCELLATION -> assertRepeatCancellation(subjectId);
            default -> throw notFound("OPS_CASE_NOT_FOUND", "Unknown case type");
        }
    }

    private void assertOverdue(String bookingId) {
        OverdueBookingsPort.Result result = overduePort.findOverdue(OperationsRules.OVERDUE_FETCH_LIMIT);
        requireAvailable(result.available(), result.reason(), "overdue bookings");
        boolean eligible = result.bookings().stream()
                .anyMatch(b -> b.bookingId().equals(bookingId));
        if (!eligible) {
            throw notFound("OPS_OVERDUE_CASE_NOT_FOUND", "No eligible overdue booking for this id");
        }
    }

    private void assertLateCase(String bookingId) {
        LateStartsPort.Result result = lateStartsPort.findLateStarts(OperationsRules.OVERDUE_FETCH_LIMIT);
        requireAvailable(result.available(), result.reason(), "late-start tracking");
        boolean eligible = result.lateStarts().stream()
                .anyMatch(l -> l.bookingId().equals(bookingId));
        if (!eligible) {
            throw notFound("OPS_LATE_CASE_NOT_FOUND", "No eligible late-start case for this id");
        }
    }

    private void assertRepeatCancellation(String userId) {
        RepeatCancellationsPort.Result result = repeatPort.findRepeatCancellations(minCount, days);
        requireAvailable(result.available(), result.reason(), "repeat cancellations");
        boolean eligible = result.cancellers().stream()
                .anyMatch(c -> c.userId().equals(userId));
        if (!eligible) {
            throw notFound("OPS_REPEAT_CASE_NOT_FOUND",
                    "This account is not a flagged repeat-cancellation case (threshold " + minCount + "/" + days + "d)");
        }
    }

    private void requireAvailable(boolean available, String reason, String source) {
        if (!available) {
            log.warn("event=OPS_ELIGIBILITY_SOURCE_UNAVAILABLE source={} reason={}", source, reason);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OPS_SOURCE_UNAVAILABLE",
                    "The " + source + " source is unavailable, so this action cannot be verified right now.");
        }
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }
}

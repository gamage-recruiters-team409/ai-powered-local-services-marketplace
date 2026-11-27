package com.gamagerecruiters.marketplace.modules.operations.port;

import java.time.Instant;
import java.util.List;

/**
 * Late starts for the operations Late cases screen (WP09): bookings past their scheduled START that have not started.
 * WP05 exposes no such query yet, so the only implementation is a stub returning {@code available = false}. Listed as a
 * WP05 dependency in {@code docs/integration/support-operations-gaps.md}.
 */
public interface LateStartsPort {

    record LateStart(String bookingId, String ref, String serviceTitle, String customerId, String providerId,
                     Instant scheduledStart, long minutesLate) {
    }

    record Result(boolean available, String reason, List<LateStart> lateStarts) {
        public static Result available(List<LateStart> lateStarts) {
            return new Result(true, null, List.copyOf(lateStarts));
        }

        public static Result unavailable(String reason) {
            return new Result(false, reason, List.of());
        }
    }

    /** At most {@code limit} late starts, most late first. */
    Result findLateStarts(int limit);
}

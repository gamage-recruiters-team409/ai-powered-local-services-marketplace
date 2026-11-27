package com.gamagerecruiters.marketplace.modules.operations.port;

import java.time.Instant;
import java.util.List;

/**
 * Repeat cancellations for the operations screen (WP09): users who cancelled at least {@code minCount} bookings in the
 * last {@code days} days. Backed by the WP05 booking stats facade. No cancellation "rate %" is exposed (WP05 does not
 * return the period booking totals — see the gaps doc).
 */
public interface RepeatCancellationsPort {

    /** {@code role} is CUSTOMER or PROVIDER, as reported by the booking module. */
    record RepeatCanceller(String userId, String role, long count, Instant lastCancelledAt) {
    }

    record Result(boolean available, String reason, List<RepeatCanceller> cancellers) {
        public static Result available(List<RepeatCanceller> cancellers) {
            return new Result(true, null, List.copyOf(cancellers));
        }

        public static Result unavailable(String reason) {
            return new Result(false, reason, List.of());
        }
    }

    /** Users at or above {@code minCount} cancellations within {@code days}, most cancellations first. */
    Result findRepeatCancellations(int minCount, int days);
}

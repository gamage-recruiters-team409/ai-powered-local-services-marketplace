package com.gamagerecruiters.marketplace.modules.operations.port;

import java.time.Instant;
import java.util.List;

/**
 * NO_SHOW disputes for the operations "No-show reports" section (WP09). Read through the dispute module's
 * {@code DisputeService}; each report carries the dispute's REAL status and links to the dispute. These are handled in
 * the dispute module — operations only surfaces them read-only (no urgency or minutes-late fields).
 */
public interface NoShowDisputesPort {

    record NoShowDispute(String disputeId, String bookingId, String customerId, String providerId, String status,
                         Instant createdAt) {
    }

    record Result(boolean available, String reason, List<NoShowDispute> disputes) {
        public static Result available(List<NoShowDispute> disputes) {
            return new Result(true, null, List.copyOf(disputes));
        }

        public static Result unavailable(String reason) {
            return new Result(false, reason, List.of());
        }
    }

    /** At most {@code limit} NO_SHOW disputes, newest first. */
    Result findNoShowDisputes(int limit);
}

package com.gamagerecruiters.marketplace.modules.adminusers.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Disputes a user is a party to, as customer or provider (WP09, dispute module). Real adapter reads disputes. */
public interface UserDisputeStatsPort {

    /** {@code side}: CUSTOMER or PROVIDER, the user's side in that dispute. */
    record RecentDispute(String id, String bookingId, String status, String category, String side, Instant createdAt) {
    }

    /** {@code open}: still active (not RESOLVED_FINAL or CLOSED). */
    record DisputeStats(long lifetime, long open, List<RecentDispute> recent) {
    }

    Optional<DisputeStats> stats(String userId, int recentLimit);
}

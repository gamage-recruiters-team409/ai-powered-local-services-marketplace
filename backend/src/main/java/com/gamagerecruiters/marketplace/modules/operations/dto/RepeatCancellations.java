package com.gamagerecruiters.marketplace.modules.operations.dto;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;

/** DTOs for the Repeat cancellations screen (WP09). No cancellation "rate %" (WP05 does not expose period totals). */
public final class RepeatCancellations {

    private RepeatCancellations() {
    }

    /** One flagged user. {@code count} is cancellations within the threshold window. */
    public record Row(String userId, String role, long count, Instant lastCancelledAt, OpsCaseStatus caseStatus,
                      LastActionView lastAction) {
    }

    public record Summary(long flagged, long customers, long providers) {
    }

    /** A pending "Suspension recommended" case shown to Admin: who recommended it and their note. */
    public record SuspensionRecommendation(String userId, String recommendedByActorId, String note,
                                           Instant recommendedAt) {
    }

    /** The screen payload, including the active threshold used. */
    public record Data(OpsPage<Row> rows, Summary summary, List<Row> top5,
                       List<SuspensionRecommendation> suspensionRecommendations, int thresholdMinCount,
                       int thresholdDays) {
    }
}

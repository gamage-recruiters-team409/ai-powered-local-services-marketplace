package com.gamagerecruiters.marketplace.modules.operations.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.gamagerecruiters.marketplace.modules.operations.domain.OpsCaseStatus;
import com.gamagerecruiters.marketplace.modules.operations.domain.OpsUrgency;

/**
 * DTOs for the Late cases screen (WP09). Two independent sections: {@code lateStarts} (a WP05 stub, so normally
 * unavailable) and {@code noShowReports} (NO_SHOW disputes with their real status). Urgency and the per-day chart only
 * appear when {@code lateStarts} is available.
 */
public final class LateCases {

    private LateCases() {
    }

    /** A late-start row. Only present when the late-start source is available. */
    public record LateStartRow(String bookingId, String ref, String serviceTitle, String customerId, String providerId,
                               Instant scheduledStart, long minutesLate, OpsUrgency urgency, OpsCaseStatus caseStatus,
                               LastActionView lastAction) {
    }

    /** {@code critical} counts CRITICAL urgency. Only present when the late-start source is available. */
    public record LateSummary(long open, long critical, long resolvedToday) {
    }

    /** The late-starts section payload (per-day covers the last 14 days). */
    public record LateStartsData(OpsPage<LateStartRow> rows, LateSummary summary, List<DayCount> perDay) {
    }

    /** A NO_SHOW dispute surfaced read-only. {@code status} is the real dispute status; no urgency / minutes-late. */
    public record NoShowReportRow(String disputeId, String bookingId, String customerId, String providerId,
                                  String status, Instant createdAt) {
    }

    /** Own counts for the No-show reports section. */
    public record NoShowCounts(long total, Map<String, Long> byStatus) {
    }

    public record NoShowReportsData(OpsPage<NoShowReportRow> rows, NoShowCounts counts) {
    }

    /** The screen: the two sections, each with its own availability. */
    public record Response(OpsSection<LateStartsData> lateStarts, OpsSection<NoShowReportsData> noShowReports) {
    }
}

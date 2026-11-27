package com.gamagerecruiters.marketplace.modules.admin.dto;

import java.time.Instant;
import java.util.List;

public record AdminAnalyticsSummaryResponse(
        MetricItem users,
        MetricItem providers,
        MetricItem bookings,
        MetricItem bookingValue,
        MetricItem commission,
        MetricItem refunds,
        MetricItem openDisputes,
        MetricItem pendingVerifications,
        MetricItem adminAccounts,
        DisputeBreakdown disputeBreakdown,
        List<AuditActivityItem> recentAuditActivity
) {
    public record MetricItem(
            Object value,
            boolean available,
            String source,
            Double deltaPct
    ) {
        public static MetricItem unavailable(String source) {
            return new MetricItem(null, false, source, null);
        }

        public static MetricItem available(Object value, String source) {
            return new MetricItem(value, true, source, null);
        }
    }

    public record DisputeBreakdown(
            long underReview,
            long awaitingResponse,
            long appealed,
            long manualReview
    ) {}

    public record AuditActivityItem(
            String id,
            String actorId,
            String action,
            String reason,
            String targetUserId,
            String outcome,
            Instant createdAt
    ) {}
}

package com.gamagerecruiters.marketplace.modules.completion.dto;

import java.time.Instant;

/**
 * Result of a no-show report. The booking is now DISPUTED and an admin decides the outcome
 * (COMPLETED, REFUNDED or PARTIALLY_REFUNDED).
 *
 * @param type      NO_SHOW_BY_CUSTOMER (reported by the provider) or NO_SHOW_BY_PROVIDER (reported by the customer)
 * @param disputeId the WP09 dispute case opened for the report, or null when it could not be opened yet
 */
public record NoShowReportResponse(
        String bookingId,
        String type,
        String bookingStatus,
        Instant reportedAt,
        String disputeId
) {

    public NoShowReportResponse(String bookingId, String type, String bookingStatus, Instant reportedAt) {
        this(bookingId, type, bookingStatus, reportedAt, null);
    }
}

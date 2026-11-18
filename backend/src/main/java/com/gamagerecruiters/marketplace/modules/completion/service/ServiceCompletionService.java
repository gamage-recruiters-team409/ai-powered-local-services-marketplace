package com.gamagerecruiters.marketplace.modules.completion.service;

import java.time.Instant;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.completion.dto.MarkCompletedRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.NoShowReportResponse;
import com.gamagerecruiters.marketplace.modules.completion.dto.RaiseIssueRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ReportNoShowRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ServiceCompletionResponse;

/**
 * Service completion flow for a booking (WP08).
 *
 * <p>This module owns the completion evidence (start time, note, photos, confirmation).
 * Booking status changes are delegated to the booking module through BookingFacade (WP05).
 */
public interface ServiceCompletionService {

    /** Provider starts the service: booking CONFIRMED -> IN_PROGRESS. */
    ServiceCompletionResponse startService(String bookingId, String providerId);

    /** Provider marks the service completed with an optional note and photos: IN_PROGRESS -> COMPLETION_PENDING. */
    ServiceCompletionResponse markCompleted(String bookingId, String providerId, MarkCompletedRequest request);

    /** Customer confirms completion: COMPLETION_PENDING -> COMPLETED. */
    ServiceCompletionResponse confirmCompletion(String bookingId, String customerId);

    /** Customer raises an issue within 48 hours of "marked completed": COMPLETION_PENDING -> DISPUTED. */
    ServiceCompletionResponse raiseIssue(String bookingId, String customerId, RaiseIssueRequest request);

    /**
     * Customer or provider reports that the other party did not show up, at least 120 minutes
     * after the booking start: CONFIRMED -> DISPUTED (an admin decides the outcome).
     */
    NoShowReportResponse reportNoShow(String bookingId, String userId, ReportNoShowRequest request);

    /** Booking IDs whose 48-hour confirmation window ended before {@code now} with no confirmation or issue. */
    List<String> findAutoConfirmCandidates(Instant now);

    /**
     * 48-hour auto-confirm for one booking (scheduler only): COMPLETION_PENDING -> COMPLETED as SYSTEM.
     * Returns false when the booking no longer qualifies (already confirmed, issue raised or status changed).
     */
    boolean autoConfirm(String bookingId);

    /** Returns the completion record for the booking's customer or provider. */
    ServiceCompletionResponse getCompletion(String bookingId, String userId);
}

package com.gamagerecruiters.marketplace.modules.completion.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.OwnershipChecker;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.domain.CompletionConfirmationType;
import com.gamagerecruiters.marketplace.modules.completion.domain.IssueType;
import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;
import com.gamagerecruiters.marketplace.modules.completion.dto.MarkCompletedRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.NoShowReportResponse;
import com.gamagerecruiters.marketplace.modules.completion.dto.RaiseIssueRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ReportNoShowRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ServiceCompletionResponse;
import com.gamagerecruiters.marketplace.modules.completion.mapper.ServiceCompletionMapper;
import com.gamagerecruiters.marketplace.modules.completion.repository.ServiceCompletionRepository;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;

/**
 * Completion rules live here; the booking status and its transition checks are owned by
 * the booking module (BookingFacade).
 *
 * <p>Errors thrown by BookingFacade (404 BOOKING_NOT_FOUND, 409 INVALID_STATUS_TRANSITION,
 * 409 BOOKING_UPDATE_CONFLICT, 403 ACCESS_DENIED) are left to propagate to the global handler.
 * The actor passed to booking is always the authenticated user (CurrentUser). The 48-hour
 * auto-confirm uses BookingFacade.markCompletedBySystem, called only from the scheduled job.
 *
 * <p>Consistency: every write runs in one MongoDB transaction ({@link CompletionTransactions})
 * that covers both the completion record and the booking status change made by BookingFacade,
 * so the two are committed together or not at all (also when the application stops between
 * the two writes). There is no manual rollback.
 *
 * <p>Concurrency: confirmation, issue raising and auto-confirmation change the completion with
 * conditional updates ({@link CompletionAtomicUpdates}); the rule that allows the change is part
 * of the update itself. A request that loses a race gets 409 and changes nothing.
 */
@Service
public class ServiceCompletionServiceImpl implements ServiceCompletionService {

    /** Customer has 48 hours after "marked completed" to confirm or raise an issue. */
    static final Duration CONFIRMATION_WINDOW = Duration.ofHours(48);
    /** A no-show can be reported 120 minutes after the booking start time. */
    static final Duration NO_SHOW_GRACE = Duration.ofMinutes(120);
    /** Booking rejects an automatic completion that is not yet 48 h after COMPLETION_PENDING. */
    static final String AUTO_COMPLETION_NOT_DUE = "AUTO_COMPLETION_NOT_DUE";

    private static final Logger log = LoggerFactory.getLogger(ServiceCompletionServiceImpl.class);

    private final ServiceCompletionRepository completionRepository;
    private final BookingFacade bookingFacade;
    private final CompletionAtomicUpdates atomicUpdates;
    private final CompletionTransactions transactions;
    private final CompletionDisputes disputes;
    private final Clock clock;

    @Autowired
    public ServiceCompletionServiceImpl(ServiceCompletionRepository completionRepository, BookingFacade bookingFacade,
                                        CompletionAtomicUpdates atomicUpdates, CompletionTransactions transactions,
                                        CompletionDisputes disputes) {
        this(completionRepository, bookingFacade, atomicUpdates, transactions, disputes, Clock.systemUTC());
    }

    /** Without the WP09 dispute link (unit tests of the completion rules). */
    ServiceCompletionServiceImpl(ServiceCompletionRepository completionRepository, BookingFacade bookingFacade,
                                 CompletionAtomicUpdates atomicUpdates, CompletionTransactions transactions,
                                 Clock clock) {
        this(completionRepository, bookingFacade, atomicUpdates, transactions, CompletionDisputes.none(), clock);
    }

    ServiceCompletionServiceImpl(ServiceCompletionRepository completionRepository, BookingFacade bookingFacade,
                                 CompletionAtomicUpdates atomicUpdates, CompletionTransactions transactions,
                                 CompletionDisputes disputes, Clock clock) {
        this.completionRepository = completionRepository;
        this.bookingFacade = bookingFacade;
        this.atomicUpdates = atomicUpdates;
        this.transactions = transactions;
        this.disputes = disputes;
        this.clock = clock;
    }

    @Override
    public ServiceCompletionResponse startService(String bookingId, String providerId) {
        return transactions.inTransaction(() -> {
            BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
            requireProvider(booking, providerId);
            requireStatus(booking, BookingStatus.CONFIRMED, "Service can only be started for a confirmed booking");

            if (completionRepository.existsByBookingId(bookingId)) {
                throw serviceAlreadyStarted();
            }

            Instant now = clock.instant();
            ServiceCompletion completion = new ServiceCompletion();
            completion.setBookingId(booking.bookingId());
            completion.setCustomerId(booking.customerId());
            completion.setProviderId(booking.providerId());
            completion.setStartedAt(now);
            completion.setCreatedAt(now);
            completion.setUpdatedAt(now);

            ServiceCompletion saved;
            try {
                saved = completionRepository.insert(completion);
            } catch (DuplicateKeyException ex) {
                // Unique bookingId index: a concurrent start already created the record
                throw serviceAlreadyStarted();
            }

            bookingFacade.markInProgress(bookingId, providerId);
            return ServiceCompletionMapper.toResponse(saved);
        });
    }

    @Override
    public ServiceCompletionResponse markCompleted(String bookingId, String providerId, MarkCompletedRequest request) {
        return transactions.inTransaction(() -> {
            BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
            requireProvider(booking, providerId);
            requireStatus(booking, BookingStatus.IN_PROGRESS, "Service can only be marked completed while it is in progress");

            ServiceCompletion current = findCompletion(bookingId);
            if (current.getMarkedCompletedAt() != null) {
                throw alreadyMarkedCompleted();
            }

            Instant now = clock.instant();
            ServiceCompletion saved = atomicUpdates.markCompleted(bookingId,
                            trimToNull(request == null ? null : request.completionNote()),
                            cleanPhotoUrls(request == null ? null : request.photoUrls()), now)
                    .orElseThrow(ServiceCompletionServiceImpl::alreadyMarkedCompleted);

            bookingFacade.markCompletionPending(bookingId, providerId);
            return ServiceCompletionMapper.toResponse(saved);
        });
    }

    @Override
    public ServiceCompletionResponse confirmCompletion(String bookingId, String customerId) {
        return transactions.inTransaction(() -> {
            BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
            requireCustomer(booking, customerId);
            requireStatus(booking, BookingStatus.COMPLETION_PENDING, "Completion can only be confirmed while it is pending");

            assertAwaitingDecision(findCompletion(bookingId));

            Instant now = clock.instant();
            ServiceCompletion saved = atomicUpdates.confirm(bookingId, CompletionConfirmationType.CUSTOMER, now, null)
                    .orElseThrow(() -> rejectedByLatestState(bookingId));

            bookingFacade.markCompleted(bookingId, customerId);
            return ServiceCompletionMapper.toResponse(saved);
        });
    }

    @Override
    public ServiceCompletionResponse raiseIssue(String bookingId, String customerId, RaiseIssueRequest request) {
        ServiceCompletionResponse raised = transactions.inTransaction(() -> {
            BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
            requireCustomer(booking, customerId);
            requireStatus(booking, BookingStatus.COMPLETION_PENDING, "An issue can only be raised while completion is pending");

            ServiceCompletion current = findCompletion(bookingId);
            assertAwaitingDecision(current);

            Instant now = clock.instant();
            Instant windowStart = now.minus(CONFIRMATION_WINDOW);
            if (current.getMarkedCompletedAt().isBefore(windowStart)) {
                throw confirmationWindowClosed();
            }

            ServiceCompletion saved = atomicUpdates.raiseIssue(bookingId, now, windowStart)
                    .orElseThrow(() -> rejectedByLatestState(bookingId));

            bookingFacade.markDisputed(bookingId, customerId, "CUSTOMER_ISSUE: " + request.reason().trim());
            return ServiceCompletionMapper.toResponse(saved);
        });

        // After commit: open the WP09 dispute case for the now DISPUTED booking and keep its ID
        String disputeId = disputes.openDispute(bookingId, customerId, Role.CUSTOMER,
                disputeCategoryFor(request.issueType()), request.reason().trim());
        if (disputeId == null) {
            return raised;
        }
        return linkDispute(raised, disputeId);
    }

    /**
     * Stores the dispute ID on the completion. The issue, the booking change and the WP09 case are already
     * committed at this point, so a failure here must not turn the request into an error (a retry would be
     * rejected because the booking is no longer COMPLETION_PENDING). The response still carries the dispute ID,
     * and {@link CompletionDisputeLinkReconciler} stores the missing link later by looking the case up in WP09
     * (read-only, so no second dispute is opened).
     */
    private ServiceCompletionResponse linkDispute(ServiceCompletionResponse raised, String disputeId) {
        String bookingId = raised.bookingId();
        try {
            return atomicUpdates.recordDisputeId(bookingId, disputeId, clock.instant())
                    .map(ServiceCompletionMapper::toResponse)
                    .orElseGet(() -> withDisputeId(raised, disputeId));
        } catch (RuntimeException ex) {
            log.warn("event=COMPLETION_DISPUTE_LINK_PENDING bookingId={} disputeId={} cause={}",
                    bookingId, disputeId, ex.getClass().getSimpleName());
            return withDisputeId(raised, disputeId);
        }
    }

    private static ServiceCompletionResponse withDisputeId(ServiceCompletionResponse response, String disputeId) {
        return new ServiceCompletionResponse(response.id(), response.bookingId(), response.startedAt(),
                response.completionNote(), response.photoUrls(), response.markedCompletedAt(), response.confirmedAt(),
                response.confirmationType(), response.issueRaisedAt(), disputeId);
    }

    /** Agreed with WP09: DIFFERENT_SERVICE has no own category yet and is sent as OTHER. */
    static DisputeCategory disputeCategoryFor(IssueType issueType) {
        if (issueType == null) {
            return DisputeCategory.OTHER;
        }
        return switch (issueType) {
            case NOT_COMPLETED -> DisputeCategory.SERVICE_NOT_DELIVERED;
            case POOR_QUALITY -> DisputeCategory.POOR_QUALITY;
            case DAMAGE -> DisputeCategory.PROPERTY_DAMAGE;
            case DIFFERENT_SERVICE, OTHER -> DisputeCategory.OTHER;
        };
    }

    @Override
    public NoShowReportResponse reportNoShow(String bookingId, String userId, ReportNoShowRequest request) {
        NoShowReportResponse reported = transactions.inTransaction(() -> {
            BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);

            String type;
            if (userId != null && userId.equals(booking.providerId())) {
                type = "NO_SHOW_BY_CUSTOMER";
            } else if (userId != null && userId.equals(booking.customerId())) {
                type = "NO_SHOW_BY_PROVIDER";
            } else {
                throw new AccessDeniedException("Access denied: only the booking's customer or provider can report a no-show");
            }

            requireStatus(booking, BookingStatus.CONFIRMED, "A no-show can only be reported for a confirmed booking");
            if (completionRepository.existsByBookingId(bookingId)) {
                throw serviceAlreadyStarted();
            }

            Instant now = clock.instant();
            if (booking.slotStart() == null || now.isBefore(booking.slotStart().plus(NO_SHOW_GRACE))) {
                throw new ResourceConflictException("NO_SHOW_TOO_EARLY",
                        "A no-show can be reported 120 minutes after the booking start time");
            }

            String note = trimToNull(request == null ? null : request.note());
            String reason = note == null ? type : type + ": " + note;
            BookingParticipants updated = bookingFacade.markDisputed(bookingId, userId, reason);

            return new NoShowReportResponse(bookingId, type,
                    updated != null && updated.status() != null ? updated.status().name() : BookingStatus.DISPUTED.name(),
                    now);
        });

        // After commit: open the WP09 dispute case (category NO_SHOW)
        boolean byProvider = "NO_SHOW_BY_CUSTOMER".equals(reported.type());
        String note = trimToNull(request == null ? null : request.note());
        String description = note != null ? note
                : byProvider ? "The customer was not available for the booking."
                : "The provider did not arrive for the booking.";
        String disputeId = disputes.openDispute(bookingId, userId, byProvider ? Role.PROVIDER : Role.CUSTOMER,
                DisputeCategory.NO_SHOW, description);
        return new NoShowReportResponse(reported.bookingId(), reported.type(), reported.bookingStatus(),
                reported.reportedAt(), disputeId);
    }

    @Override
    public List<String> findAutoConfirmCandidates(Instant now) {
        return completionRepository
                .findByMarkedCompletedAtBeforeAndConfirmedAtIsNullAndIssueRaisedAtIsNull(now.minus(CONFIRMATION_WINDOW))
                .stream()
                .map(ServiceCompletion::getBookingId)
                .toList();
    }

    @Override
    public boolean autoConfirm(String bookingId) {
        try {
            return transactions.inTransaction(() -> confirmAutomatically(bookingId));
        } catch (ApiException ex) {
            if (AUTO_COMPLETION_NOT_DUE.equals(ex.getErrorCode())) {
                // Booking entered COMPLETION_PENDING slightly later than our record: retried on the next run
                log.info("Auto-confirm not due yet in booking for {}; will retry on the next run", bookingId);
                return false;
            }
            throw ex;
        }
    }

    private boolean confirmAutomatically(String bookingId) {
        Instant now = clock.instant();
        Instant dueIfMarkedBefore = now.minus(CONFIRMATION_WINDOW);

        ServiceCompletion completion = completionRepository.findByBookingId(bookingId).orElse(null);
        boolean stillDue = completion != null
                && completion.getMarkedCompletedAt() != null
                && completion.getConfirmedAt() == null
                && completion.getIssueRaisedAt() == null
                && !completion.getMarkedCompletedAt().isAfter(dueIfMarkedBefore);
        if (!stillDue) {
            return false;
        }

        BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
        if (booking.status() != BookingStatus.COMPLETION_PENDING) {
            log.warn("Auto-confirm skipped for booking {}: status is {}", bookingId, booking.status());
            return false;
        }

        if (atomicUpdates.confirm(bookingId, CompletionConfirmationType.AUTO, now, dueIfMarkedBefore).isEmpty()) {
            // The customer confirmed or raised an issue in the meantime: nothing to do
            return false;
        }

        // Trusted system path (scheduled job only); booking checks the 48 h from its own timeline
        bookingFacade.markCompletedBySystem(bookingId);
        return true;
    }

    @Override
    public ServiceCompletionResponse getCompletion(String bookingId, String userId) {
        BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
        boolean participant = userId != null
                && (userId.equals(booking.customerId()) || userId.equals(booking.providerId()));
        if (!participant) {
            // Same 403 ACCESS_DENIED response as the team OwnershipChecker / BookingFacade
            throw new AccessDeniedException("Access denied: only the booking's customer or provider can view its completion");
        }
        return ServiceCompletionMapper.toResponse(findCompletion(bookingId));
    }

    // ------------------------------------------------------------------ helpers

    private ServiceCompletion findCompletion(String bookingId) {
        return completionRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("COMPLETION_NOT_FOUND",
                        "The service has not been started for this booking"));
    }

    private static void requireProvider(BookingParticipants booking, String providerId) {
        OwnershipChecker.assertOwner(providerId, booking.providerId());
    }

    private static void requireCustomer(BookingParticipants booking, String customerId) {
        OwnershipChecker.assertOwner(customerId, booking.customerId());
    }

    private static void requireStatus(BookingParticipants booking, BookingStatus expected, String message) {
        if (booking.status() != expected) {
            throw new ResourceConflictException("INVALID_STATUS_TRANSITION",
                    message + " (current status: " + booking.status() + ")");
        }
    }

    /** Marked completed, not confirmed and no issue raised yet. */
    private static void assertAwaitingDecision(ServiceCompletion completion) {
        if (completion.getMarkedCompletedAt() == null) {
            throw new ResourceConflictException("SERVICE_NOT_MARKED_COMPLETED",
                    "The provider has not marked this service as completed yet");
        }
        if (completion.getConfirmedAt() != null) {
            throw alreadyConfirmed();
        }
        if (completion.getIssueRaisedAt() != null) {
            throw issueRaised();
        }
    }

    /**
     * The conditional update matched nothing: another request changed the record after it was read.
     * Explain why, using the latest stored record; nothing was written by this request.
     */
    private ResourceConflictException rejectedByLatestState(String bookingId) {
        ServiceCompletion latest = completionRepository.findByBookingId(bookingId).orElse(null);
        if (latest != null && latest.getConfirmedAt() != null) {
            return alreadyConfirmed();
        }
        if (latest != null && latest.getIssueRaisedAt() != null) {
            return issueRaised();
        }
        return MongoCompletionTransactions.updateConflict();
    }

    private static ResourceConflictException alreadyMarkedCompleted() {
        return new ResourceConflictException("SERVICE_ALREADY_MARKED_COMPLETED",
                "This service has already been marked as completed");
    }

    private static ResourceConflictException alreadyConfirmed() {
        return new ResourceConflictException("COMPLETION_ALREADY_CONFIRMED", "This completion has already been confirmed");
    }

    private static ResourceConflictException issueRaised() {
        return new ResourceConflictException("COMPLETION_ISSUE_RAISED",
                "An issue has been raised for this service, so it can no longer be confirmed or changed");
    }

    private static ResourceConflictException confirmationWindowClosed() {
        return new ResourceConflictException("CONFIRMATION_WINDOW_CLOSED",
                "Issues can be raised within 48 hours of the service being marked as completed");
    }

    private static ResourceConflictException serviceAlreadyStarted() {
        return new ResourceConflictException("SERVICE_ALREADY_STARTED",
                "The service has already been started for this booking");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> cleanPhotoUrls(List<String> photoUrls) {
        List<String> cleaned = new ArrayList<>();
        if (photoUrls != null) {
            for (String url : photoUrls) {
                String trimmed = trimToNull(url);
                if (trimmed != null) {
                    cleaned.add(trimmed);
                }
            }
        }
        return cleaned;
    }
}

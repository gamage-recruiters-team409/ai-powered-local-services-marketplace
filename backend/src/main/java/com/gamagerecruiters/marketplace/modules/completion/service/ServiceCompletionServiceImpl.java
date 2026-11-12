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
import com.gamagerecruiters.marketplace.modules.auth.security.OwnershipChecker;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.domain.CompletionConfirmationType;
import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;
import com.gamagerecruiters.marketplace.modules.completion.dto.MarkCompletedRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.NoShowReportResponse;
import com.gamagerecruiters.marketplace.modules.completion.dto.RaiseIssueRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ReportNoShowRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ServiceCompletionResponse;
import com.gamagerecruiters.marketplace.modules.completion.mapper.ServiceCompletionMapper;
import com.gamagerecruiters.marketplace.modules.completion.repository.ServiceCompletionRepository;

/**
 * Completion rules live here; the booking status and its transition checks are owned by
 * the booking module (BookingFacade).
 *
 * <p>Errors thrown by BookingFacade (404 BOOKING_NOT_FOUND, 409 INVALID_STATUS_TRANSITION,
 * 409 BOOKING_UPDATE_CONFLICT, 403 ACCESS_DENIED) are left to propagate to the global handler.
 * The actor passed to booking is always the authenticated user (CurrentUser). The 48-hour
 * auto-confirm uses BookingFacade.markCompletedBySystem, called only from the scheduled job.
 *
 * <p>Each write saves the completion evidence first and then asks booking to change the
 * status. If booking rejects the change, the completion record is put back as it was, so
 * the two never disagree.
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
    private final Clock clock;

    @Autowired
    public ServiceCompletionServiceImpl(ServiceCompletionRepository completionRepository, BookingFacade bookingFacade) {
        this(completionRepository, bookingFacade, Clock.systemUTC());
    }

    ServiceCompletionServiceImpl(ServiceCompletionRepository completionRepository, BookingFacade bookingFacade, Clock clock) {
        this.completionRepository = completionRepository;
        this.bookingFacade = bookingFacade;
        this.clock = clock;
    }

    @Override
    public ServiceCompletionResponse startService(String bookingId, String providerId) {
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
            saved = completionRepository.save(completion);
        } catch (DuplicateKeyException ex) {
            // Unique bookingId index: a concurrent start already created the record
            throw serviceAlreadyStarted();
        }

        try {
            bookingFacade.markInProgress(bookingId, providerId);
        } catch (RuntimeException ex) {
            // Booking rejected the transition: remove the record we just created
            completionRepository.delete(saved);
            throw ex;
        }

        return ServiceCompletionMapper.toResponse(saved);
    }

    @Override
    public ServiceCompletionResponse markCompleted(String bookingId, String providerId, MarkCompletedRequest request) {
        BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
        requireProvider(booking, providerId);
        requireStatus(booking, BookingStatus.IN_PROGRESS, "Service can only be marked completed while it is in progress");

        ServiceCompletion completion = findCompletion(bookingId);
        if (completion.getMarkedCompletedAt() != null) {
            throw new ResourceConflictException("SERVICE_ALREADY_MARKED_COMPLETED",
                    "This service has already been marked as completed");
        }

        String previousNote = completion.getCompletionNote();
        List<String> previousPhotos = completion.getPhotoUrls();
        Instant previousUpdatedAt = completion.getUpdatedAt();

        Instant now = clock.instant();
        completion.setCompletionNote(trimToNull(request == null ? null : request.completionNote()));
        completion.setPhotoUrls(cleanPhotoUrls(request == null ? null : request.photoUrls()));
        completion.setMarkedCompletedAt(now);
        completion.setUpdatedAt(now);
        ServiceCompletion saved = completionRepository.save(completion);

        try {
            bookingFacade.markCompletionPending(bookingId, providerId);
        } catch (RuntimeException ex) {
            saved.setCompletionNote(previousNote);
            saved.setPhotoUrls(previousPhotos);
            saved.setMarkedCompletedAt(null);
            saved.setUpdatedAt(previousUpdatedAt);
            completionRepository.save(saved);
            throw ex;
        }

        return ServiceCompletionMapper.toResponse(saved);
    }

    @Override
    public ServiceCompletionResponse confirmCompletion(String bookingId, String customerId) {
        BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
        requireCustomer(booking, customerId);
        requireStatus(booking, BookingStatus.COMPLETION_PENDING, "Completion can only be confirmed while it is pending");

        ServiceCompletion completion = findCompletion(bookingId);
        if (completion.getMarkedCompletedAt() == null) {
            throw new ResourceConflictException("SERVICE_NOT_MARKED_COMPLETED",
                    "The provider has not marked this service as completed yet");
        }
        if (completion.getConfirmedAt() != null) {
            throw new ResourceConflictException("COMPLETION_ALREADY_CONFIRMED",
                    "This completion has already been confirmed");
        }
        if (completion.getIssueRaisedAt() != null) {
            throw new ResourceConflictException("COMPLETION_ISSUE_RAISED",
                    "An issue has been raised for this service, so it cannot be confirmed");
        }

        Instant previousUpdatedAt = completion.getUpdatedAt();
        Instant now = clock.instant();
        completion.setConfirmedAt(now);
        completion.setConfirmationType(CompletionConfirmationType.CUSTOMER);
        completion.setUpdatedAt(now);
        ServiceCompletion saved = completionRepository.save(completion);

        try {
            bookingFacade.markCompleted(bookingId, customerId);
        } catch (RuntimeException ex) {
            saved.setConfirmedAt(null);
            saved.setConfirmationType(null);
            saved.setUpdatedAt(previousUpdatedAt);
            completionRepository.save(saved);
            throw ex;
        }

        return ServiceCompletionMapper.toResponse(saved);
    }

    @Override
    public ServiceCompletionResponse raiseIssue(String bookingId, String customerId, RaiseIssueRequest request) {
        BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
        requireCustomer(booking, customerId);
        requireStatus(booking, BookingStatus.COMPLETION_PENDING, "An issue can only be raised while completion is pending");

        ServiceCompletion completion = findCompletion(bookingId);
        if (completion.getMarkedCompletedAt() == null) {
            throw new ResourceConflictException("SERVICE_NOT_MARKED_COMPLETED",
                    "The provider has not marked this service as completed yet");
        }
        if (completion.getConfirmedAt() != null) {
            throw new ResourceConflictException("COMPLETION_ALREADY_CONFIRMED",
                    "This completion has already been confirmed");
        }
        if (completion.getIssueRaisedAt() != null) {
            throw new ResourceConflictException("COMPLETION_ISSUE_RAISED", "An issue has already been raised for this service");
        }

        Instant now = clock.instant();
        if (now.isAfter(completion.getMarkedCompletedAt().plus(CONFIRMATION_WINDOW))) {
            throw new ResourceConflictException("CONFIRMATION_WINDOW_CLOSED",
                    "Issues can be raised within 48 hours of the service being marked as completed");
        }

        Instant previousUpdatedAt = completion.getUpdatedAt();
        completion.setIssueRaisedAt(now);
        completion.setUpdatedAt(now);
        ServiceCompletion saved = completionRepository.save(completion);

        try {
            bookingFacade.markDisputed(bookingId, customerId, "CUSTOMER_ISSUE: " + request.reason().trim());
        } catch (RuntimeException ex) {
            saved.setIssueRaisedAt(null);
            saved.setUpdatedAt(previousUpdatedAt);
            completionRepository.save(saved);
            throw ex;
        }

        return ServiceCompletionMapper.toResponse(saved);
    }

    @Override
    public NoShowReportResponse reportNoShow(String bookingId, String userId, ReportNoShowRequest request) {
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
        ServiceCompletion completion = completionRepository.findByBookingId(bookingId).orElse(null);
        Instant now = clock.instant();
        boolean stillDue = completion != null
                && completion.getMarkedCompletedAt() != null
                && completion.getConfirmedAt() == null
                && completion.getIssueRaisedAt() == null
                && !now.isBefore(completion.getMarkedCompletedAt().plus(CONFIRMATION_WINDOW));
        if (!stillDue) {
            return false;
        }

        BookingParticipants booking = bookingFacade.getBookingParticipants(bookingId);
        if (booking.status() != BookingStatus.COMPLETION_PENDING) {
            log.warn("Auto-confirm skipped for booking {}: status is {}", bookingId, booking.status());
            return false;
        }

        Instant previousUpdatedAt = completion.getUpdatedAt();
        completion.setConfirmedAt(now);
        completion.setConfirmationType(CompletionConfirmationType.AUTO);
        completion.setUpdatedAt(now);
        ServiceCompletion saved = completionRepository.save(completion);

        try {
            // Trusted system path (scheduled job only); booking checks the 48 h from its own timeline
            bookingFacade.markCompletedBySystem(bookingId);
        } catch (RuntimeException ex) {
            saved.setConfirmedAt(null);
            saved.setConfirmationType(null);
            saved.setUpdatedAt(previousUpdatedAt);
            completionRepository.save(saved);
            if (ex instanceof ApiException api && AUTO_COMPLETION_NOT_DUE.equals(api.getErrorCode())) {
                // Booking entered COMPLETION_PENDING slightly later than our record: try again next run
                log.info("Auto-confirm not due yet in booking for {}; will retry on the next run", bookingId);
                return false;
            }
            throw ex;
        }
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

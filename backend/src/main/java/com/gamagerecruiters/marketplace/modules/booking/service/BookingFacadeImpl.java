package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.OwnershipChecker;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@Service
public class BookingFacadeImpl implements BookingFacade {

    /** BR-007: completion is auto-confirmed 48 hours after the provider marks completion. */
    static final Duration AUTO_COMPLETE_AFTER = Duration.ofHours(48);

    /** BR-008: the dispute window closes 72 hours after completion confirmation. */
    static final Duration DISPUTE_WINDOW = Duration.ofHours(72);

    private static final Set<BookingStatus> FROM_CONFIRMED = EnumSet.of(BookingStatus.CONFIRMED);
    private static final Set<BookingStatus> FROM_IN_PROGRESS = EnumSet.of(BookingStatus.IN_PROGRESS);
    private static final Set<BookingStatus> FROM_COMPLETION_PENDING = EnumSet.of(BookingStatus.COMPLETION_PENDING);
    private static final Set<BookingStatus> DISPUTABLE = EnumSet.of(
            BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETION_PENDING,
            BookingStatus.COMPLETED);
    private static final Set<BookingStatus> FROM_DISPUTED = EnumSet.of(BookingStatus.DISPUTED);

    /** Final statuses an admin dispute decision can set (SRS 5.2). */
    static final Set<BookingStatus> DISPUTE_FINAL_STATUSES = EnumSet.of(
            BookingStatus.COMPLETED, BookingStatus.REFUNDED, BookingStatus.PARTIALLY_REFUNDED);

    /** Same role the dispute module requires for decisions (ADMIN area edit). */
    private static final Role DISPUTE_RESOLVER_ROLE = Role.ADMIN;

    private static final int MAX_REFERENCE_LENGTH = 100;

    /** Which booking participant the authenticated actor must be. */
    private enum Participant {
        PROVIDER,
        CUSTOMER,
        CUSTOMER_OR_PROVIDER
    }

    private final BookingRepository bookingRepository;
    private final Clock clock;

    @Autowired
    public BookingFacadeImpl(BookingRepository bookingRepository) {
        this(bookingRepository, Clock.systemUTC());
    }

    BookingFacadeImpl(BookingRepository bookingRepository, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    @Override
    public boolean hasOpenBookingsForCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return false;
        }
        return bookingRepository.existsByCustomerIdAndStatusIn(customerId, BookingStatus.OPEN_STATUSES);
    }

    @Override
    public BookingParticipants getBookingParticipants(String bookingId) {
        return toParticipants(findBooking(bookingId));
    }

    @Override
    public BookingParticipants markInProgress(String bookingId, String providerId) {
        return changeStatusByUser(bookingId, providerId, Participant.PROVIDER, FROM_CONFIRMED,
                BookingStatus.IN_PROGRESS, null);
    }

    @Override
    public BookingParticipants markCompletionPending(String bookingId, String providerId) {
        return changeStatusByUser(bookingId, providerId, Participant.PROVIDER, FROM_IN_PROGRESS,
                BookingStatus.COMPLETION_PENDING, null);
    }

    @Override
    public BookingParticipants markCompleted(String bookingId, String customerId) {
        return changeStatusByUser(bookingId, customerId, Participant.CUSTOMER, FROM_COMPLETION_PENDING,
                BookingStatus.COMPLETED, null);
    }

    @Override
    public BookingParticipants markCompletedBySystem(String bookingId) {
        assertTrustedSystemContext();

        Booking booking = findBooking(bookingId);
        assertStartsFrom(booking, FROM_COMPLETION_PENDING, BookingStatus.COMPLETED);

        Instant now = Instant.now(clock);
        Instant pendingSince = lastChangeTo(booking, BookingStatus.COMPLETION_PENDING).orElse(null);
        if (pendingSince == null || now.isBefore(pendingSince.plus(AUTO_COMPLETE_AFTER))) {
            throw new ResourceConflictException("AUTO_COMPLETION_NOT_DUE",
                    "Automatic completion is only allowed 48 hours after the provider marked completion");
        }

        return applyAndSave(booking, BookingStatus.COMPLETED, BookingStatusChange.SYSTEM,
                "Auto-confirmed after 48 hours", now);
    }

    @Override
    public BookingParticipants markDisputed(String bookingId, String actorId, String reason) {
        String cleanReason = (reason == null || reason.isBlank()) ? null : reason.trim();
        return changeStatusByUser(bookingId, actorId, Participant.CUSTOMER_OR_PROVIDER, DISPUTABLE,
                BookingStatus.DISPUTED, cleanReason);
    }

    @Override
    public BookingParticipants assertDisputeEligible(String bookingId, String actorId) {
        assertActorIsAuthenticatedUser(actorId);

        Booking booking = findBooking(bookingId);
        assertParticipant(booking, actorId, Participant.CUSTOMER_OR_PROVIDER);

        Instant now = Instant.now(clock);
        if (booking.getStatus() == BookingStatus.DISPUTED) {
            assertWithinWindowSince(booking, BookingStatus.DISPUTED, now);
        } else {
            assertStartsFrom(booking, DISPUTABLE, BookingStatus.DISPUTED);
            if (booking.getStatus() == BookingStatus.COMPLETED) {
                assertWithinDisputeWindow(booking, now);
            }
        }
        // Read-only: nothing is saved.
        return toParticipants(booking);
    }

    @Override
    public BookingParticipants resolveDispute(String bookingId, BookingStatus finalStatus, String reference) {
        String changedBy = disputeResolverId();

        if (finalStatus == null || !DISPUTE_FINAL_STATUSES.contains(finalStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DISPUTE_RESOLUTION",
                    "A dispute can only be resolved to COMPLETED, REFUNDED or PARTIALLY_REFUNDED");
        }

        Booking booking = findBooking(bookingId);

        // Retry of a decision that was already applied: return it unchanged.
        if (alreadyResolvedTo(booking, finalStatus)) {
            return toParticipants(booking);
        }

        assertStartsFrom(booking, FROM_DISPUTED, finalStatus);

        String cleanReference = cleanReference(reference);
        String reason = cleanReference == null ? "Dispute resolved" : "Dispute resolved (" + cleanReference + ")";
        return applyAndSave(booking, finalStatus, changedBy, reason, Instant.now(clock));
    }

    /**
     * ADMIN in a request → their user ID; internal job (no request, no login) → SYSTEM.
     * A request without login → 401; any other role → 403.
     */
    private String disputeResolverId() {
        if (CurrentUser.isAuthenticated()) {
            if (CurrentUser.getRole() != DISPUTE_RESOLVER_ROLE) {
                throw new AccessDeniedException("Access denied: only an admin can resolve a dispute");
            }
            return CurrentUser.getUserId();
        }
        if (RequestContextHolder.getRequestAttributes() != null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
        return BookingStatusChange.SYSTEM;
    }

    /** True when the latest timeline entry is DISPUTED → {@code finalStatus} (an applied decision). */
    private boolean alreadyResolvedTo(Booking booking, BookingStatus finalStatus) {
        if (booking.getStatus() != finalStatus || booking.getTimeline().isEmpty()) {
            return false;
        }
        BookingStatusChange last = booking.getTimeline().get(booking.getTimeline().size() - 1);
        return last.getFromStatus() == BookingStatus.DISPUTED && last.getToStatus() == finalStatus;
    }

    private static String cleanReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String trimmed = reference.trim();
        return trimmed.length() > MAX_REFERENCE_LENGTH ? trimmed.substring(0, MAX_REFERENCE_LENGTH) : trimmed;
    }

    /**
     * Authenticated actor → load → participant check → starting status (+ dispute window) → save.
     * Identity and participant are checked before the status so a non-participant cannot learn it.
     */
    private BookingParticipants changeStatusByUser(String bookingId, String actorId, Participant participant,
                                                   Set<BookingStatus> allowedFrom, BookingStatus target,
                                                   String reason) {
        assertActorIsAuthenticatedUser(actorId);

        Booking booking = findBooking(bookingId);
        assertParticipant(booking, actorId, participant);
        assertStartsFrom(booking, allowedFrom, target);

        Instant now = Instant.now(clock);
        if (target == BookingStatus.DISPUTED && booking.getStatus() == BookingStatus.COMPLETED) {
            assertWithinDisputeWindow(booking, now);
        }

        return applyAndSave(booking, target, actorId, reason, now);
    }

    private BookingParticipants applyAndSave(Booking booking, BookingStatus target, String changedBy,
                                             String reason, Instant now) {
        booking.changeStatus(target, changedBy, reason, now);
        try {
            return toParticipants(bookingRepository.save(booking));
        } catch (OptimisticLockingFailureException e) {
            throw new ResourceConflictException("BOOKING_UPDATE_CONFLICT",
                    "The booking was updated at the same time. Please reload and try again.");
        }
    }

    /** The supplied actor must be the logged-in user; callers cannot act as someone else. */
    private void assertActorIsAuthenticatedUser(String actorId) {
        String authenticatedUserId = CurrentUser.getUserId();
        if (authenticatedUserId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
        if (actorId == null || !authenticatedUserId.equals(actorId)) {
            throw new AccessDeniedException("Access denied: actor does not match the authenticated user");
        }
    }

    /** SYSTEM changes run only from internal jobs: no HTTP request and no logged-in user. */
    private void assertTrustedSystemContext() {
        if (RequestContextHolder.getRequestAttributes() != null || CurrentUser.isAuthenticated()) {
            throw new AccessDeniedException("Access denied: system completion is only allowed from internal jobs");
        }
    }

    private void assertParticipant(Booking booking, String actorId, Participant participant) {
        switch (participant) {
            case PROVIDER -> OwnershipChecker.assertOwner(actorId, booking.getProviderId());
            case CUSTOMER -> OwnershipChecker.assertOwner(actorId, booking.getCustomerId());
            case CUSTOMER_OR_PROVIDER -> {
                if (!actorId.equals(booking.getCustomerId()) && !actorId.equals(booking.getProviderId())) {
                    throw new AccessDeniedException("Access denied: actor is not a participant of this booking");
                }
            }
            default -> throw new AccessDeniedException("Access denied");
        }
    }

    /** Each method accepts only its own starting statuses, even if the general state model allows more. */
    private void assertStartsFrom(Booking booking, Set<BookingStatus> allowedFrom, BookingStatus target) {
        if (booking.getStatus() == null || !allowedFrom.contains(booking.getStatus())) {
            throw new ResourceConflictException("INVALID_STATUS_TRANSITION",
                    "Booking cannot move from " + booking.getStatus() + " to " + target + " here");
        }
    }

    private void assertWithinDisputeWindow(Booking booking, Instant now) {
        assertWithinWindowSince(booking, BookingStatus.COMPLETED, now);
    }

    /** 72 h window from the last time the booking entered {@code status}; missing time fails closed. */
    private void assertWithinWindowSince(Booking booking, BookingStatus status, Instant now) {
        Instant since = lastChangeTo(booking, status).orElse(null);
        if (since == null || now.isAfter(since.plus(DISPUTE_WINDOW))) {
            throw new ResourceConflictException("DISPUTE_WINDOW_CLOSED",
                    "The dispute window closes 72 hours after " + (status == BookingStatus.COMPLETED
                            ? "completion" : "the booking was disputed"));
        }
    }

    private Optional<Instant> lastChangeTo(Booking booking, BookingStatus status) {
        Instant last = null;
        for (BookingStatusChange change : booking.getTimeline()) {
            if (change.getToStatus() == status && change.getChangedAt() != null) {
                last = change.getChangedAt();
            }
        }
        return Optional.ofNullable(last);
    }

    private Booking findBooking(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) {
            throw new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found");
        }
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found"));
    }

    private BookingParticipants toParticipants(Booking booking) {
        return new BookingParticipants(
                booking.getId(),
                booking.getSeriesId(),
                booking.getListingId(),
                booking.getCustomerId(),
                booking.getProviderId(),
                booking.getStatus(),
                booking.getSlotStart(),
                booking.getSlotEnd());
    }
}

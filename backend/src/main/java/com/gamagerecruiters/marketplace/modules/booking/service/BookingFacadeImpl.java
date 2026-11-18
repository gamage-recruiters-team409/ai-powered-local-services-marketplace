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
        Instant completedAt = lastChangeTo(booking, BookingStatus.COMPLETED).orElse(null);
        if (completedAt == null || now.isAfter(completedAt.plus(DISPUTE_WINDOW))) {
            throw new ResourceConflictException("DISPUTE_WINDOW_CLOSED",
                    "The dispute window closes 72 hours after completion");
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

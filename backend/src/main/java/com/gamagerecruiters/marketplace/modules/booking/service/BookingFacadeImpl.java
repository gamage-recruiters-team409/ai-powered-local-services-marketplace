package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.OwnershipChecker;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

@Service
public class BookingFacadeImpl implements BookingFacade {

    /** Who may perform a status change. */
    private enum AllowedActor {
        PROVIDER,
        CUSTOMER_OR_SYSTEM,
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
        return changeStatus(bookingId, BookingStatus.IN_PROGRESS, providerId, AllowedActor.PROVIDER, null);
    }

    @Override
    public BookingParticipants markCompletionPending(String bookingId, String providerId) {
        return changeStatus(bookingId, BookingStatus.COMPLETION_PENDING, providerId, AllowedActor.PROVIDER, null);
    }

    @Override
    public BookingParticipants markCompleted(String bookingId, String actorId) {
        return changeStatus(bookingId, BookingStatus.COMPLETED, actorId, AllowedActor.CUSTOMER_OR_SYSTEM, null);
    }

    @Override
    public BookingParticipants markDisputed(String bookingId, String actorId, String reason) {
        String cleanReason = (reason == null || reason.isBlank()) ? null : reason.trim();
        return changeStatus(bookingId, BookingStatus.DISPUTED, actorId, AllowedActor.CUSTOMER_OR_PROVIDER,
                cleanReason);
    }

    /**
     * Load → check actor → check transition → save with optimistic locking.
     * The actor is checked before the transition so a non-participant cannot learn the booking status.
     */
    private BookingParticipants changeStatus(String bookingId, BookingStatus target, String actorId,
                                             AllowedActor allowedActor, String reason) {
        Booking booking = findBooking(bookingId);

        assertActorAllowed(booking, actorId, allowedActor);

        Instant now = Instant.now(clock);
        booking.changeStatus(target, actorId, reason, now);

        try {
            return toParticipants(bookingRepository.save(booking));
        } catch (OptimisticLockingFailureException e) {
            throw new ResourceConflictException("BOOKING_UPDATE_CONFLICT",
                    "The booking was updated at the same time. Please reload and try again.");
        }
    }

    private void assertActorAllowed(Booking booking, String actorId, AllowedActor allowedActor) {
        if (actorId == null || actorId.isBlank()) {
            throw new AccessDeniedException("Access denied: actor is required");
        }

        switch (allowedActor) {
            case PROVIDER -> OwnershipChecker.assertOwner(actorId, booking.getProviderId());
            case CUSTOMER_OR_SYSTEM -> {
                if (!SYSTEM_ACTOR.equals(actorId)) {
                    OwnershipChecker.assertOwner(actorId, booking.getCustomerId());
                }
            }
            case CUSTOMER_OR_PROVIDER -> {
                if (!actorId.equals(booking.getCustomerId()) && !actorId.equals(booking.getProviderId())) {
                    throw new AccessDeniedException("Access denied: actor is not a participant of this booking");
                }
            }
            default -> throw new AccessDeniedException("Access denied");
        }
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

package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.OwnershipChecker;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingDeclineReason;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingDecisionResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.DeclineBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;

/**
 * Provider decision on a booking request.
 * <ul>
 *   <li>Only the booking's provider (logged in) can decide; only from PENDING_PROVIDER; only until the
 *       response deadline (C-02) – after it the expiry job moves the request to EXPIRED.</li>
 *   <li>Accept: provider must still be APPROVED (WP02); → AWAITING_PAYMENT with acceptedAt and
 *       paymentDueAt. The slot stays held (AWAITING_PAYMENT is an open status).</li>
 *   <li>Decline: dropdown reason, free text only for OTHER (C-09); → DECLINED, slot released.</li>
 * </ul>
 */
@Service
public class ProviderBookingDecisionServiceImpl implements ProviderBookingDecisionService {

    private static final Logger log = LoggerFactory.getLogger(ProviderBookingDecisionServiceImpl.class);

    private final BookingRepository bookingRepository;
    private final ProviderVerificationFacade providerVerification;
    private final BookingPaymentPolicy paymentPolicy;
    private final Clock clock;

    @Autowired
    public ProviderBookingDecisionServiceImpl(BookingRepository bookingRepository,
                                              ProviderVerificationFacade providerVerification,
                                              BookingPaymentPolicy paymentPolicy) {
        this(bookingRepository, providerVerification, paymentPolicy, Clock.systemUTC());
    }

    ProviderBookingDecisionServiceImpl(BookingRepository bookingRepository,
                                       ProviderVerificationFacade providerVerification,
                                       BookingPaymentPolicy paymentPolicy, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.providerVerification = providerVerification;
        this.paymentPolicy = paymentPolicy;
        this.clock = clock;
    }

    @Override
    public BookingDecisionResponse accept(String bookingId) {
        String providerId = currentUserId();
        Instant now = Instant.now(clock);
        Booking booking = loadPendingForProvider(bookingId, providerId, now);

        if (!providerVerification.isApprovedProvider(providerId)) {
            throw new ResourceConflictException("PROVIDER_NOT_BOOKABLE",
                    "Your provider account cannot accept bookings right now");
        }

        booking.changeStatus(BookingStatus.AWAITING_PAYMENT, providerId, "Accepted by provider", now);
        booking.setAcceptedAt(now);
        booking.setPaymentDueAt(paymentPolicy.paymentDueAt(now));
        Booking saved = save(booking);
        log.info("Booking accepted: ref={}, paymentDueAt={}", saved.getRef(), saved.getPaymentDueAt());
        return toResponse(saved);
    }

    @Override
    public BookingDecisionResponse decline(String bookingId, DeclineBookingRequest request) {
        String providerId = currentUserId();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (request.reason() == BookingDeclineReason.OTHER && note == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DECLINE_NOTE_REQUIRED",
                    "Please describe the reason when you choose Other");
        }
        if (request.reason() != BookingDeclineReason.OTHER && note != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DECLINE_NOTE_NOT_ALLOWED",
                    "A note is only allowed when the reason is Other");
        }

        Instant now = Instant.now(clock);
        Booking booking = loadPendingForProvider(bookingId, providerId, now);
        String reason = "Declined: " + (note == null ? request.reason().getLabel() : "Other - " + note);
        booking.changeStatus(BookingStatus.DECLINED, providerId, reason, now);
        Booking saved = save(booking);
        log.info("Booking declined: ref={}, reason={}", saved.getRef(), request.reason());
        return toResponse(saved);
    }

    private static String currentUserId() {
        String userId = CurrentUser.getUserId();
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
        return userId;
    }

    private Booking loadPendingForProvider(String bookingId, String providerId, Instant now) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found"));
        OwnershipChecker.assertOwner(providerId, booking.getProviderId());
        if (booking.getStatus() != BookingStatus.PENDING_PROVIDER) {
            throw new ResourceConflictException("INVALID_STATUS_TRANSITION",
                    "Only a pending booking request can be accepted or declined");
        }
        if (booking.getResponseDeadline() != null && now.isAfter(booking.getResponseDeadline())) {
            throw new ResourceConflictException("RESPONSE_DEADLINE_PASSED",
                    "The response time for this request has passed");
        }
        return booking;
    }

    private Booking save(Booking booking) {
        try {
            return bookingRepository.save(booking);
        } catch (OptimisticLockingFailureException e) {
            throw new ResourceConflictException("BOOKING_UPDATE_CONFLICT",
                    "The booking was updated at the same time. Please reload and try again.");
        }
    }

    private static BookingDecisionResponse toResponse(Booking booking) {
        return new BookingDecisionResponse(booking.getId(), booking.getRef(), booking.getStatus(),
                booking.getAcceptedAt(), booking.getPaymentDueAt());
    }
}

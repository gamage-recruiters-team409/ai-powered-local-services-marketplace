package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingPriceSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPaymentSnapshot;
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
    private static final Set<BookingStatus> FROM_AWAITING_PAYMENT = EnumSet.of(BookingStatus.AWAITING_PAYMENT);

    /** Final statuses an admin dispute decision can set (SRS 5.2). */
    static final Set<BookingStatus> DISPUTE_FINAL_STATUSES = EnumSet.of(
            BookingStatus.COMPLETED, BookingStatus.REFUNDED, BookingStatus.PARTIALLY_REFUNDED);

    /** Same role the dispute module requires for decisions (ADMIN area edit). */
    private static final Role DISPUTE_RESOLVER_ROLE = Role.ADMIN;

    private static final Set<Role> PAYMENT_READ_ROLES = EnumSet.of(Role.ADMIN, Role.SUPER_ADMIN, Role.FINANCE_ADMIN);

    /** Payment provider reference (e.g. Stripe session / payment intent id). */
    private static final Pattern PAYMENT_REFERENCE = Pattern.compile("^[A-Za-z0-9_.:-]{1,100}$");

    /** WP09 settlement idempotency key: dispute-<disputeId> (SettlementInstruction.idempotencyKey). */
    private static final Pattern DISPUTE_REFERENCE = Pattern.compile("^dispute-[A-Za-z0-9_-]{1,92}$");

    /** Which booking participant the authenticated actor must be. */
    private enum Participant {
        PROVIDER,
        CUSTOMER,
        CUSTOMER_OR_PROVIDER
    }

    private final BookingRepository bookingRepository;
    private final BookingPaymentPolicy paymentPolicy;
    private final Clock clock;

    @Autowired
    public BookingFacadeImpl(BookingRepository bookingRepository, BookingPaymentPolicy paymentPolicy) {
        this(bookingRepository, paymentPolicy, Clock.systemUTC());
    }

    BookingFacadeImpl(BookingRepository bookingRepository, BookingPaymentPolicy paymentPolicy, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.paymentPolicy = paymentPolicy;
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
        if (reference == null || !DISPUTE_REFERENCE.matcher(reference).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DISPUTE_REFERENCE",
                    "The dispute reference must be the settlement key dispute-<disputeId>");
        }

        Booking booking = findBooking(bookingId);

        if (booking.getStatus() != BookingStatus.DISPUTED) {
            BookingStatusChange applied = appliedResolution(booking);
            if (applied != null) {
                // Retry of the same decision: return it unchanged, save nothing.
                if (applied.getToStatus() == finalStatus && reference.equals(applied.getReference())) {
                    return toParticipants(booking);
                }
                throw new ResourceConflictException("DISPUTE_ALREADY_RESOLVED",
                        "This dispute was already resolved by a different decision");
            }
        }

        assertStartsFrom(booking, FROM_DISPUTED, finalStatus);

        booking.changeStatus(finalStatus, changedBy, "Dispute resolved", Instant.now(clock));
        booking.getTimeline().get(booking.getTimeline().size() - 1).setReference(reference);
        return save(booking);
    }

    // ---------- WP07 payment ----------

    @Override
    public BookingPaymentSnapshot getPaymentSnapshot(String bookingId) {
        boolean trusted = TrustedBookingSystemCall.isActive(TrustedBookingSystemCall.Purpose.PAYMENT_PROCESSING);
        if (!trusted && !CurrentUser.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
        Booking booking = findBooking(bookingId);
        if (!trusted && !PAYMENT_READ_ROLES.contains(CurrentUser.getRole())) {
            OwnershipChecker.assertOwner(CurrentUser.getUserId(), booking.getCustomerId());
        }
        BookingPriceSnapshot price = booking.getPriceSnapshot();
        if (price == null) {
            throw new ResourceConflictException("PRICE_SNAPSHOT_MISSING",
                    "This booking has no captured price and cannot be paid");
        }
        return new BookingPaymentSnapshot(booking.getId(), booking.getRef(), booking.getCustomerId(),
                booking.getProviderId(), booking.getListingId(), booking.getStatus(), price.totalPrice(),
                price.currency(), price.commissionRate(), price.capturedAt(), booking.getAcceptedAt(),
                booking.getPaymentDueAt());
    }

    @Override
    public BookingParticipants markPaymentConfirmed(String bookingId, String paymentReference) {
        assertPaymentProcessing(paymentReference);
        Booking booking = findBooking(bookingId);

        // Checked on the whole timeline, not the current status: a gateway retry can arrive after the
        // booking has moved on (IN_PROGRESS, COMPLETED, DISPUTED ...). Same reference → unchanged.
        BookingStatusChange applied = latestChange(booking, BookingStatus.AWAITING_PAYMENT, BookingStatus.CONFIRMED);
        if (applied != null) {
            if (paymentReference.equals(applied.getReference())) {
                return toParticipants(booking);
            }
            throw new ResourceConflictException("PAYMENT_ALREADY_APPLIED",
                    "This booking was already confirmed by a different payment");
        }
        assertStartsFrom(booking, FROM_AWAITING_PAYMENT, BookingStatus.CONFIRMED);

        booking.changeStatus(BookingStatus.CONFIRMED, BookingStatusChange.SYSTEM, "Payment confirmed",
                Instant.now(clock));
        booking.getTimeline().get(booking.getTimeline().size() - 1).setReference(paymentReference);
        return save(booking);
    }

    @Override
    public BookingParticipants markPaymentExpired(String bookingId, String paymentReference) {
        assertPaymentProcessing(paymentReference);
        Booking booking = findBooking(bookingId);

        if (booking.getStatus() == BookingStatus.EXPIRED
                && latestChange(booking, BookingStatus.AWAITING_PAYMENT, BookingStatus.EXPIRED) != null) {
            return toParticipants(booking);
        }
        assertStartsFrom(booking, FROM_AWAITING_PAYMENT, BookingStatus.EXPIRED);
        Instant now = Instant.now(clock);
        // Same rule as BookingExpiryJob: only after paymentDueAt + grace, so a payment that started just
        // before the deadline can still be confirmed during the grace period.
        if (!paymentPolicy.isPaymentTimedOut(booking.getPaymentDueAt(), now)) {
            throw new ResourceConflictException("PAYMENT_NOT_DUE",
                    "The payment time for this booking, including the grace period, has not passed yet");
        }

        booking.changeStatus(BookingStatus.EXPIRED, BookingStatusChange.SYSTEM, "Payment not completed", now);
        booking.getTimeline().get(booking.getTimeline().size() - 1).setReference(paymentReference);
        return save(booking);
    }

    /** Payment changes only from WP07 inside a PAYMENT_PROCESSING trusted call; reference must be valid. */
    private static void assertPaymentProcessing(String paymentReference) {
        if (!TrustedBookingSystemCall.isActive(TrustedBookingSystemCall.Purpose.PAYMENT_PROCESSING)) {
            if (CurrentUser.isAuthenticated()) {
                throw new AccessDeniedException("Access denied: payment status is changed by the payment module only");
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
        if (paymentReference == null || !PAYMENT_REFERENCE.matcher(paymentReference).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_REFERENCE",
                    "A valid payment reference is required");
        }
    }

    /** Most recent timeline change from {@code from} to {@code to}, anywhere in the history (or null). */
    private static BookingStatusChange latestChange(Booking booking, BookingStatus from, BookingStatus to) {
        List<BookingStatusChange> timeline = booking.getTimeline();
        for (int i = timeline.size() - 1; i >= 0; i--) {
            BookingStatusChange change = timeline.get(i);
            if (change.getFromStatus() == from && change.getToStatus() == to) {
                return change;
            }
        }
        return null;
    }

    /**
     * Authenticated ADMIN → their user ID. The WP09 settlement job inside
     * {@link TrustedBookingSystemCall#run} (purpose DISPUTE_SETTLEMENT, no HTTP request) → SYSTEM.
     * No authentication → 401 (also outside HTTP requests); any other role → 403.
     */
    private String disputeResolverId() {
        if (TrustedBookingSystemCall.isActive(TrustedBookingSystemCall.Purpose.DISPUTE_SETTLEMENT)
                && RequestContextHolder.getRequestAttributes() == null) {
            return BookingStatusChange.SYSTEM;
        }
        if (CurrentUser.isAuthenticated()) {
            if (CurrentUser.getRole() != DISPUTE_RESOLVER_ROLE) {
                throw new AccessDeniedException("Access denied: only an admin can resolve a dispute");
            }
            return CurrentUser.getUserId();
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
    }

    /**
     * The applied dispute decision, when the latest timeline entry is DISPUTED → a final status
     * and the booking is still in that status; otherwise null.
     */
    private static BookingStatusChange appliedResolution(Booking booking) {
        if (booking.getTimeline().isEmpty()) {
            return null;
        }
        BookingStatusChange last = booking.getTimeline().get(booking.getTimeline().size() - 1);
        boolean resolution = last.getFromStatus() == BookingStatus.DISPUTED
                && DISPUTE_FINAL_STATUSES.contains(last.getToStatus())
                && booking.getStatus() == last.getToStatus();
        return resolution ? last : null;
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
        return save(booking);
    }

    private BookingParticipants save(Booking booking) {
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

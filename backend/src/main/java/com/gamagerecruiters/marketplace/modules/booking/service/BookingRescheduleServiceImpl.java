package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleRequest;
import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.AvailableSlotsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.RescheduleView;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

/**
 * Reschedule flow. Only CONFIRMED bookings; one PENDING request at a time; at most 2 approved moves; the request
 * must be made at least 6 h before the current start; the other party answers within 12 h or before the current
 * start. Approval re-checks the new slot inside a MongoDB transaction that claims the provider's booking guard,
 * the same protection as booking creation, so it can never double-book the provider.
 */
@Service
public class BookingRescheduleServiceImpl implements BookingRescheduleService {

    static final int MAX_TRANSACTION_ATTEMPTS = 50;
    static final long DEFAULT_RETRY_DELAY_MILLIS = 100;

    private static final Logger log = LoggerFactory.getLogger(BookingRescheduleServiceImpl.class);

    private final BookingRepository bookingRepository;
    private final SlotService slotService;
    private final ProviderBookingGuard providerBookingGuard;
    private final ReschedulePolicy policy;
    private final BookingNotifier notifier;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final long retryDelayMillis;

    @Autowired
    public BookingRescheduleServiceImpl(BookingRepository bookingRepository, SlotService slotService,
                                        ProviderBookingGuard providerBookingGuard, ReschedulePolicy policy,
                                        BookingNotifier notifier,
                                        ObjectProvider<MongoTransactionManager> transactionManager) {
        this(bookingRepository, slotService, providerBookingGuard, policy, notifier,
                transactionTemplate(transactionManager.getIfAvailable()), Clock.systemUTC(),
                DEFAULT_RETRY_DELAY_MILLIS);
    }

    BookingRescheduleServiceImpl(BookingRepository bookingRepository, SlotService slotService,
                                 ProviderBookingGuard providerBookingGuard, ReschedulePolicy policy,
                                 BookingNotifier notifier, TransactionOperations transactions, Clock clock,
                                 long retryDelayMillis) {
        this.bookingRepository = bookingRepository;
        this.slotService = slotService;
        this.providerBookingGuard = providerBookingGuard;
        this.policy = policy;
        this.notifier = notifier;
        this.transactions = transactions == null ? TransactionOperations.withoutTransaction() : transactions;
        this.clock = clock;
        this.retryDelayMillis = retryDelayMillis;
    }

    private static TransactionOperations transactionTemplate(MongoTransactionManager manager) {
        return manager == null ? null : new TransactionTemplate(manager);
    }

    @Override
    public AvailableSlotsResponse availableSlots(String bookingId, String date) {
        Booking booking = loadForParticipant(bookingId, currentUserId());
        assertConfirmed(booking);
        return slotService.getAvailableSlots(booking.getProviderId(), date,
                String.valueOf(booking.getDurationMinutes()), booking.getId());
    }

    @Override
    public RescheduleResponse request(String bookingId, RescheduleBookingRequest body) {
        String userId = currentUserId();
        Booking booking = loadForParticipant(bookingId, userId);
        Instant now = Instant.now(clock);
        validateNewRequest(booking, body.newSlotStart(), now);
        return submit(booking, userId, body.newSlotStart(), body.note(), now);
    }

    /**
     * All checks for a new request on this booking, without saving anything. Also used by the block-date flow
     * to check every "request new time" decision before it changes any booking.
     */
    void validateNewRequest(Booking booking, Instant newSlotStart, Instant now) {
        assertConfirmed(booking);
        if (booking.pendingReschedule().isPresent()) {
            throw new ResourceConflictException("RESCHEDULE_ALREADY_PENDING",
                    "There is already a reschedule request waiting for an answer");
        }
        if (policy.remaining(booking.approvedRescheduleCount()) == 0) {
            throw new ResourceConflictException("RESCHEDULE_LIMIT_REACHED",
                    "This booking has already been rescheduled " + policy.maxReschedules() + " times");
        }
        if (policy.isTooLate(booking.getSlotStart(), now)) {
            throw new ResourceConflictException("RESCHEDULE_TOO_LATE",
                    "A new time can only be requested at least 6 hours before the start");
        }
        if (newSlotStart.equals(booking.getSlotStart())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESCHEDULE_SAME_TIME",
                    "Please choose a time different from the current one");
        }
        if (!slotService.isSlotAvailable(booking.getProviderId(), newSlotStart, booking.getDurationMinutes(),
                booking.getId())) {
            throw new ResourceConflictException("SLOT_UNAVAILABLE",
                    "This time slot is not available. Please choose another slot.");
        }
    }

    /** Saves a new PENDING request (already validated) and notifies the other party. */
    RescheduleResponse submit(Booking booking, String userId, Instant newSlotStart, String note, Instant now) {
        RescheduleRequest request = new RescheduleRequest();
        request.setId(UUID.randomUUID().toString());
        request.setRequestedBy(userId);
        request.setRequestedByRole(roleOf(booking, userId));
        request.setPreviousSlotStart(booking.getSlotStart());
        request.setPreviousSlotEnd(booking.getSlotEnd());
        request.setProposedSlotStart(newSlotStart);
        request.setProposedSlotEnd(newSlotStart.plus(Duration.ofMinutes(booking.getDurationMinutes())));
        request.setNote(note == null || note.isBlank() ? null : note.trim());
        request.setStatus(RescheduleStatus.PENDING);
        request.setRequestedAt(now);
        request.setExpiresAt(policy.expiresAt(now, booking.getSlotStart()));
        booking.getRescheduleRequests().add(request);
        booking.setUpdatedAt(now);

        Booking saved = save(booking);
        log.info("event=RESCHEDULE_REQUESTED ref={} by={} proposed={}", saved.getRef(), request.getRequestedByRole(),
                request.getProposedSlotStart());
        notifier.rescheduleRequested(saved, request);
        return toResponse(saved, request);
    }

    @Override
    public RescheduleResponse approve(String bookingId) {
        String userId = currentUserId();
        Booking first = loadForParticipant(bookingId, userId);
        providerBookingGuard.ensureExists(first.getProviderId(), Instant.now(clock));

        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                Booking saved = transactions.execute(status -> approveOnce(bookingId, userId));
                RescheduleRequest approved = lastDecided(saved);
                log.info("event=RESCHEDULE_APPROVED ref={} newStart={}", saved.getRef(), saved.getSlotStart());
                notifier.rescheduleApproved(saved, approved);
                return toResponse(saved, approved);
            } catch (RuntimeException e) {
                if (!BookingRequestServiceImpl.isTransientTransactionError(e)) {
                    throw e;
                }
                sleepBeforeRetry();
            }
        }
        throw new ResourceConflictException("BOOKING_BUSY",
                "Another booking for this provider is being processed. Please try again.");
    }

    /** Runs inside the transaction: claim the provider guard, then re-read and re-check everything. */
    private Booking approveOnce(String bookingId, String userId) {
        Instant now = Instant.now(clock);
        Booking booking = loadForParticipant(bookingId, userId);
        providerBookingGuard.claim(booking.getProviderId(), now);
        RescheduleRequest request = pendingForResponder(booking, userId, now);
        assertConfirmed(booking);
        if (!slotService.isSlotAvailable(booking.getProviderId(), request.getProposedSlotStart(),
                booking.getDurationMinutes(), booking.getId())) {
            throw new ResourceConflictException("RESCHEDULE_SLOT_UNAVAILABLE",
                    "The proposed time is no longer available. Please reject it and propose another time.");
        }
        booking.setSlotStart(request.getProposedSlotStart());
        booking.setSlotEnd(request.getProposedSlotEnd());
        decide(request, RescheduleStatus.APPROVED, userId, now);
        booking.setUpdatedAt(now);
        return save(booking);
    }

    @Override
    public RescheduleResponse reject(String bookingId) {
        String userId = currentUserId();
        Booking booking = loadForParticipant(bookingId, userId);
        Instant now = Instant.now(clock);
        RescheduleRequest request = pendingForResponder(booking, userId, now);
        decide(request, RescheduleStatus.REJECTED, userId, now);
        booking.setUpdatedAt(now);
        Booking saved = save(booking);
        log.info("event=RESCHEDULE_REJECTED ref={}", saved.getRef());
        notifier.rescheduleRejected(saved, request);
        return toResponse(saved, request);
    }

    @Override
    public RescheduleResponse withdraw(String bookingId) {
        String userId = currentUserId();
        Booking booking = loadForParticipant(bookingId, userId);
        Instant now = Instant.now(clock);
        RescheduleRequest request = booking.pendingReschedule().orElseThrow(this::noPending);
        if (!userId.equals(request.getRequestedBy())) {
            throw new ResourceConflictException("RESCHEDULE_NOT_REQUESTER",
                    "Only the person who asked for the new time can withdraw it");
        }
        decide(request, RescheduleStatus.WITHDRAWN, userId, now);
        booking.setUpdatedAt(now);
        Booking saved = save(booking);
        log.info("event=RESCHEDULE_WITHDRAWN ref={}", saved.getRef());
        return toResponse(saved, request);
    }

    // ---------- helpers ----------

    private RescheduleRequest pendingForResponder(Booking booking, String userId, Instant now) {
        RescheduleRequest request = booking.pendingReschedule().orElseThrow(this::noPending);
        if (userId.equals(request.getRequestedBy())) {
            throw new ResourceConflictException("RESCHEDULE_OWN_REQUEST",
                    "You cannot answer your own reschedule request");
        }
        if (!now.isBefore(request.getExpiresAt())) {
            throw new ResourceConflictException("RESCHEDULE_REQUEST_EXPIRED",
                    "This reschedule request has expired");
        }
        return request;
    }

    private static void decide(RescheduleRequest request, RescheduleStatus outcome, String userId, Instant now) {
        request.setStatus(outcome);
        request.setRespondedBy(userId);
        request.setRespondedAt(now);
    }

    private static RescheduleRequest lastDecided(Booking booking) {
        return booking.getRescheduleRequests().get(booking.getRescheduleRequests().size() - 1);
    }

    private ResourceConflictException noPending() {
        return new ResourceConflictException("NO_PENDING_RESCHEDULE", "There is no reschedule request to answer");
    }

    private static void assertConfirmed(Booking booking) {
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ResourceConflictException("RESCHEDULE_NOT_ALLOWED",
                    "Only a confirmed booking can be rescheduled");
        }
    }

    private Booking loadForParticipant(String bookingId, String userId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found"));
        if (!userId.equals(booking.getCustomerId()) && !userId.equals(booking.getProviderId())) {
            throw new AccessDeniedException("Access denied: you are not part of this booking");
        }
        return booking;
    }

    private static String roleOf(Booking booking, String userId) {
        return userId.equals(booking.getCustomerId()) ? "CUSTOMER" : "PROVIDER";
    }

    private static String currentUserId() {
        String userId = CurrentUser.getUserId();
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
        return userId;
    }

    private Booking save(Booking booking) {
        try {
            return bookingRepository.save(booking);
        } catch (OptimisticLockingFailureException e) {
            throw new ResourceConflictException("BOOKING_UPDATE_CONFLICT",
                    "The booking was updated at the same time. Please reload and try again.");
        }
    }

    private RescheduleResponse toResponse(Booking booking, RescheduleRequest request) {
        return new RescheduleResponse(booking.getId(), booking.getRef(), booking.getStatus(), booking.getSlotStart(),
                booking.getSlotEnd(), RescheduleView.from(request),
                policy.remaining(booking.approvedRescheduleCount()));
    }

    private void sleepBeforeRetry() {
        if (retryDelayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(retryDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceConflictException("BOOKING_BUSY",
                    "Another booking for this provider is being processed. Please try again.");
        }
    }
}

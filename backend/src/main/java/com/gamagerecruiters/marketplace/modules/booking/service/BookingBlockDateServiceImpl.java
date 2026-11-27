package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.availability.dto.BlockedDateResponse;
import com.gamagerecruiters.marketplace.modules.availability.dto.CreateBlockedDateRequest;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityFacade;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingDeclineReason;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateAction;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateDecision;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateImpactResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateResult;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateWithBookingsRequest;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

/**
 * Block a date that has bookings, as in the WP05 design: each confirmed booking is kept or gets a new-time
 * request; pending requests on that day are declined automatically (no penalty); while a payment is in progress
 * (AWAITING_PAYMENT) the date cannot be blocked yet.
 * <p>
 * Everything is checked first (decisions complete, every new time valid) and only then applied, so a bad
 * decision never leaves some bookings changed and others not.
 */
@Service
public class BookingBlockDateServiceImpl implements BookingBlockDateService {

    static final String DECLINE_REASON = "Declined: Provider blocked this date";

    private static final Logger log = LoggerFactory.getLogger(BookingBlockDateServiceImpl.class);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(SlotServiceImpl.BUSINESS_ZONE);

    private final BookingRepository bookingRepository;
    private final AvailabilityFacade availabilityFacade;
    private final BookingRescheduleServiceImpl rescheduleService;
    private final ReschedulePolicy reschedulePolicy;
    private final BookingNotifier notifier;
    private final ProviderBookingGuard providerBookingGuard;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final long retryDelayMillis;

    @Autowired
    public BookingBlockDateServiceImpl(BookingRepository bookingRepository, AvailabilityFacade availabilityFacade,
                                       BookingRescheduleServiceImpl rescheduleService,
                                       ReschedulePolicy reschedulePolicy, BookingNotifier notifier,
                                       ProviderBookingGuard providerBookingGuard,
                                       ObjectProvider<MongoTransactionManager> transactionManager) {
        this(bookingRepository, availabilityFacade, rescheduleService, reschedulePolicy, notifier,
                providerBookingGuard, transactionTemplate(transactionManager.getIfAvailable()), Clock.systemUTC(),
                BookingRescheduleServiceImpl.DEFAULT_RETRY_DELAY_MILLIS);
    }

    BookingBlockDateServiceImpl(BookingRepository bookingRepository, AvailabilityFacade availabilityFacade,
                                BookingRescheduleServiceImpl rescheduleService, ReschedulePolicy reschedulePolicy,
                                BookingNotifier notifier, ProviderBookingGuard providerBookingGuard,
                                TransactionOperations transactions, Clock clock, long retryDelayMillis) {
        this.bookingRepository = bookingRepository;
        this.availabilityFacade = availabilityFacade;
        this.rescheduleService = rescheduleService;
        this.reschedulePolicy = reschedulePolicy;
        this.notifier = notifier;
        this.providerBookingGuard = providerBookingGuard;
        this.transactions = transactions;
        this.clock = clock;
        this.retryDelayMillis = retryDelayMillis;
    }

    private static TransactionOperations transactionTemplate(MongoTransactionManager manager) {
        return manager == null ? null : new TransactionTemplate(manager);
    }

    @Override
    public BlockDateImpactResponse impact(String dateText) {
        String providerId = currentUserId();
        LocalDate date = parseDate(dateText);
        Instant now = Instant.now(clock);

        List<BlockDateImpactResponse.Item> confirmed = new ArrayList<>();
        List<BlockDateImpactResponse.Item> pending = new ArrayList<>();
        List<BlockDateImpactResponse.Item> awaiting = new ArrayList<>();
        for (Booking b : BookingBlockedDateCheck.bookingsOnDate(bookingRepository, providerId, date)) {
            BlockDateImpactResponse.Item item = toItem(b, now);
            switch (b.getStatus()) {
                case CONFIRMED -> confirmed.add(item);
                case PENDING_PROVIDER -> pending.add(item);
                case AWAITING_PAYMENT -> awaiting.add(item);
                default -> {
                    // not affected
                }
            }
        }
        return new BlockDateImpactResponse(date.toString(), confirmed, pending, awaiting, awaiting.isEmpty());
    }

    @Override
    public BlockDateResult blockWithBookings(BlockDateWithBookingsRequest request) {
        String providerId = currentUserId();
        LocalDate date = parseDate(request.date());
        if (date.isBefore(LocalDate.now(clock.withZone(SlotServiceImpl.BUSINESS_ZONE)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BLOCKED_DATE", "Cannot block a past date");
        }
        if (transactions == null) {
            // Fail closed: without a transaction the changes could not be atomic or serialised with bookings.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BLOCK_DATE_UNAVAILABLE",
                    "Blocking dates is not available right now. Please try again later.");
        }
        providerBookingGuard.ensureExists(providerId, Instant.now(clock));

        for (int attempt = 1; attempt <= BookingRescheduleServiceImpl.MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                BlockDateResult result = transactions.execute(status -> blockOnce(providerId, date, request));
                log.info("event=DATE_BLOCKED_WITH_BOOKINGS date={} declined={} kept={} newTimeRequested={}", date,
                        result.declinedBookingIds().size(), result.keptBookingIds().size(),
                        result.newTimeRequestedBookingIds().size());
                return result;
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

    /**
     * One attempt, inside a MongoDB transaction: claim the provider booking guard first (the same serialisation
     * point as booking creation and reschedule approval), then read the bookings fresh, check everything, apply,
     * and save the blocked date. Any failure rolls back every change; notifications are sent only after commit
     * ({@link BookingNotifier} waits for the commit when a transaction is open).
     */
    private BlockDateResult blockOnce(String providerId, LocalDate date, BlockDateWithBookingsRequest request) {
        Instant now = Instant.now(clock);
        providerBookingGuard.claim(providerId, now);
        if (availabilityFacade.isDateBlocked(providerId, date)) {
            throw new ResourceConflictException("BLOCKED_DATE_EXISTS", "This date is already blocked");
        }

        List<Booking> onDate = BookingBlockedDateCheck.bookingsOnDate(bookingRepository, providerId, date);
        if (onDate.stream().anyMatch(b -> b.getStatus() == BookingStatus.AWAITING_PAYMENT)) {
            throw new ResourceConflictException("BLOCK_DATE_PAYMENT_IN_PROGRESS",
                    "A customer is paying for a booking on this date. Please try again in a few minutes.");
        }
        List<Booking> confirmed = onDate.stream().filter(b -> b.getStatus() == BookingStatus.CONFIRMED).toList();
        List<Booking> pending = onDate.stream().filter(b -> b.getStatus() == BookingStatus.PENDING_PROVIDER).toList();

        Map<String, BlockDateDecision> decisions = decisionsById(request.decisions(), confirmed);

        // 1. Check every decision before changing anything.
        for (Booking booking : confirmed) {
            BlockDateDecision decision = decisions.get(booking.getId());
            if (decision.action() == BlockDateAction.REQUEST_NEW_TIME) {
                if (decision.newSlotStart() == null) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "NEW_TIME_REQUIRED",
                            "Please choose a new time for booking " + booking.getRef());
                }
                if (decision.newSlotStart().atZone(SlotServiceImpl.BUSINESS_ZONE).toLocalDate().equals(date)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "NEW_TIME_ON_BLOCKED_DATE",
                            "The new time for booking " + booking.getRef() + " is on the date you are blocking");
                }
                rescheduleService.validateNewRequest(booking, decision.newSlotStart(), now);
            }
        }

        // 2. Apply (all in this transaction).
        List<String> declined = new ArrayList<>();
        for (Booking booking : pending) {
            booking.changeStatus(BookingStatus.DECLINED, providerId, DECLINE_REASON, now);
            Booking saved = save(booking);
            declined.add(saved.getId());
            notifier.declined(saved, BookingDeclineReason.NOT_AVAILABLE);
        }
        List<String> kept = new ArrayList<>();
        List<String> newTimeRequested = new ArrayList<>();
        Set<String> handled = new HashSet<>();
        for (Booking booking : confirmed) {
            BlockDateDecision decision = decisions.get(booking.getId());
            handled.add(booking.getId());
            if (decision.action() == BlockDateAction.REQUEST_NEW_TIME) {
                rescheduleService.submit(booking, providerId, decision.newSlotStart(), decision.note(), now);
                newTimeRequested.add(booking.getId());
            } else {
                kept.add(booking.getId());
            }
        }

        // 3. Block the date in the same transaction; the booking check accepts the bookings decided above.
        BlockedDateResponse blocked = BlockDateContext.runWithHandled(handled, () ->
                availabilityFacade.createBlockedDate(providerId,
                        new CreateBlockedDateRequest(date.toString(), request.reason(), request.note())));
        return new BlockDateResult(blocked, declined, kept, newTimeRequested);
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

    private static Map<String, BlockDateDecision> decisionsById(List<BlockDateDecision> list, List<Booking> confirmed) {
        Set<String> confirmedIds = new HashSet<>();
        confirmed.forEach(b -> confirmedIds.add(b.getId()));
        Map<String, BlockDateDecision> byId = new HashMap<>();
        for (BlockDateDecision d : list == null ? List.<BlockDateDecision>of() : list) {
            if (!confirmedIds.contains(d.bookingId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_BOOKING_DECISION",
                        "Booking " + d.bookingId() + " is not a confirmed booking on this date");
            }
            if (byId.put(d.bookingId(), d) != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_DECISION",
                        "Only one decision per booking is allowed");
            }
        }
        List<String> missing = confirmed.stream().filter(b -> !byId.containsKey(b.getId())).map(Booking::getRef)
                .toList();
        if (!missing.isEmpty()) {
            throw new ResourceConflictException("DECISION_REQUIRED",
                    "Please choose what to do with booking(s) " + String.join(", ", missing));
        }
        return byId;
    }

    private BlockDateImpactResponse.Item toItem(Booking b, Instant now) {
        boolean reschedulePending = b.pendingReschedule().isPresent();
        boolean canRequestNewTime = b.getStatus() == BookingStatus.CONFIRMED && !reschedulePending
                && reschedulePolicy.remaining(b.approvedRescheduleCount()) > 0
                && !reschedulePolicy.isTooLate(b.getSlotStart(), now);
        return new BlockDateImpactResponse.Item(b.getId(), b.getRef(), b.getStatus(),
                b.getPriceSnapshot() == null ? null : b.getPriceSnapshot().serviceTitle(), b.getCustomerId(),
                b.getSlotStart(), b.getSlotEnd(), LOCAL_TIME.format(b.getSlotStart()),
                b.getSlotEnd() == null ? null : LOCAL_TIME.format(b.getSlotEnd()), reschedulePending,
                canRequestNewTime);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "date is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "date must be in yyyy-MM-dd format");
        }
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
                    "A booking on this date changed at the same time. Please reload and try again.");
        }
    }
}

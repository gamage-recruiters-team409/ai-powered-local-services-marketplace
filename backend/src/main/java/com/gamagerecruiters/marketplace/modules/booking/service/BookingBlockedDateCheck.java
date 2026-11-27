package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.availability.service.BlockedDateBookingCheck;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

/**
 * Stops a date from being blocked while it still has bookings nobody decided about, and serialises date blocking
 * with booking creation through the provider booking guard. The plain POST /availability/me/blocked-dates
 * therefore only works for an empty day; a day with bookings goes through POST /bookings/blocked-dates.
 */
@Component
public class BookingBlockedDateCheck implements BlockedDateBookingCheck {

    /** Bookings that still hold a future time on that day. */
    static final Set<BookingStatus> AFFECTED_STATUSES = EnumSet.of(BookingStatus.PENDING_PROVIDER,
            BookingStatus.AWAITING_PAYMENT, BookingStatus.CONFIRMED);

    private final BookingRepository bookingRepository;
    private final ProviderBookingGuard providerBookingGuard;
    private final TransactionOperations transactions;
    private final Clock clock;

    @Autowired
    public BookingBlockedDateCheck(BookingRepository bookingRepository, ProviderBookingGuard providerBookingGuard,
                                   ObjectProvider<MongoTransactionManager> transactionManager) {
        this(bookingRepository, providerBookingGuard, transactionTemplate(transactionManager.getIfAvailable()),
                Clock.systemUTC());
    }

    BookingBlockedDateCheck(BookingRepository bookingRepository, ProviderBookingGuard providerBookingGuard,
                            TransactionOperations transactions, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.providerBookingGuard = providerBookingGuard;
        this.transactions = transactions;
        this.clock = clock;
    }

    private static TransactionOperations transactionTemplate(MongoTransactionManager manager) {
        return manager == null ? null : new TransactionTemplate(manager);
    }

    @Override
    public <T> T createIfNoBookingsToHandle(String providerId, LocalDate date, Supplier<T> createBlockedDate) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Called from the block-date flow, which already runs in a transaction that claimed the guard
            // (and owns the retries): check and save as part of it.
            return checkAndCreate(providerId, date, createBlockedDate);
        }
        if (transactions == null) {
            // Fail closed: without a transaction the guard cannot serialise this with booking creation.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BLOCK_DATE_UNAVAILABLE",
                    "Blocking dates is not available right now. Please try again later.");
        }
        Instant now = Instant.now(clock);
        providerBookingGuard.ensureExists(providerId, now);
        for (int attempt = 1; attempt <= BookingRescheduleServiceImpl.MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                return transactions.execute(status -> {
                    providerBookingGuard.claim(providerId, Instant.now(clock));
                    return checkAndCreate(providerId, date, createBlockedDate);
                });
            } catch (RuntimeException e) {
                if (!BookingRequestServiceImpl.isTransientTransactionError(e)) {
                    throw e;
                }
                pauseBeforeRetry();
            }
        }
        throw new ResourceConflictException("BOOKING_BUSY",
                "Another booking for this provider is being processed. Please try again.");
    }

    private static void pauseBeforeRetry() {
        try {
            Thread.sleep(BookingRescheduleServiceImpl.DEFAULT_RETRY_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceConflictException("BOOKING_BUSY",
                    "Another booking for this provider is being processed. Please try again.");
        }
    }

    private <T> T checkAndCreate(String providerId, LocalDate date, Supplier<T> createBlockedDate) {
        Set<String> handled = BlockDateContext.handled();
        long open = bookingsOnDate(bookingRepository, providerId, date).stream()
                .filter(b -> !handled.contains(b.getId()))
                .count();
        if (open > 0) {
            throw new ResourceConflictException("BOOKINGS_ON_DATE",
                    "This date has " + open + " booking(s). Please choose what to do with them before blocking it.");
        }
        return createBlockedDate.get();
    }

    /** Affected bookings that start on this date (Asia/Colombo), earliest first. */
    static List<Booking> bookingsOnDate(BookingRepository repository, String providerId, LocalDate date) {
        Instant dayStart = date.atStartOfDay(SlotServiceImpl.BUSINESS_ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(SlotServiceImpl.BUSINESS_ZONE).toInstant();
        return repository.findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(
                        providerId, AFFECTED_STATUSES, dayEnd, dayStart).stream()
                .filter(b -> b.getSlotStart() != null && !b.getSlotStart().isBefore(dayStart))
                .sorted((a, b) -> a.getSlotStart().compareTo(b.getSlotStart()))
                .toList();
    }
}

package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

/**
 * Releases slots held by unanswered or unpaid bookings (recorded as SYSTEM):
 * <ul>
 *   <li>PENDING_PROVIDER past its response deadline (C-02) → EXPIRED</li>
 *   <li>AWAITING_PAYMENT past paymentDueAt + grace → EXPIRED</li>
 * </ul>
 * Internal to the booking module (no facade call). A booking changed concurrently (e.g. accepted or paid
 * at the same moment) is skipped thanks to optimistic locking and looked at again on the next run.
 * Switch off with {@code booking.expiry.enabled=false}.
 */
@Component
public class BookingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryJob.class);

    /** Result of one run. */
    public record Result(int requestsExpired, int paymentsExpired) {
    }

    private final BookingRepository bookingRepository;
    private final BookingPaymentPolicy paymentPolicy;
    private final Clock clock;
    private final boolean enabled;
    private final BookingNotifier notifier;

    @Autowired
    public BookingExpiryJob(BookingRepository bookingRepository, BookingPaymentPolicy paymentPolicy,
                            BookingNotifier notifier,
                            @Value("${booking.expiry.enabled:true}") boolean enabled) {
        this(bookingRepository, paymentPolicy, notifier, Clock.systemUTC(), enabled);
    }

    BookingExpiryJob(BookingRepository bookingRepository, BookingPaymentPolicy paymentPolicy,
                     BookingNotifier notifier, Clock clock, boolean enabled) {
        this.notifier = notifier;
        this.bookingRepository = bookingRepository;
        this.paymentPolicy = paymentPolicy;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${booking.expiry.interval:PT1M}",
            initialDelayString = "${booking.expiry.initial-delay:PT1M}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        try {
            Result result = run();
            if (result.requestsExpired() + result.paymentsExpired() > 0) {
                log.info("event=BOOKING_EXPIRY_RUN requestsExpired={} paymentsExpired={}",
                        result.requestsExpired(), result.paymentsExpired());
            }
        } catch (RuntimeException e) {
            log.error("event=BOOKING_EXPIRY_FAILED cause={}", e.getClass().getSimpleName(), e);
        }
    }

    public Result run() {
        Instant now = Instant.now(clock);

        List<Booking> unanswered = bookingRepository
                .findTop100ByStatusAndResponseDeadlineBeforeOrderByResponseDeadlineAsc(
                        BookingStatus.PENDING_PROVIDER, now);
        int requests = expireAll(unanswered, "Provider did not respond in time", now, notifier::requestExpired);

        List<Booking> unpaid = bookingRepository
                .findTop100ByStatusAndPaymentDueAtBeforeOrderByPaymentDueAtAsc(
                        // paymentDueAt < now - grace: the same rule as BookingPaymentPolicy.isPaymentTimedOut,
                        // which markPaymentExpired (WP07) also uses.
                        BookingStatus.AWAITING_PAYMENT, paymentPolicy.expireDueBefore(now));
        int payments = expireAll(unpaid, "Payment not completed in time", now, notifier::paymentExpired);

        return new Result(requests, payments);
    }

    private int expireAll(List<Booking> bookings, String reason, Instant now, Consumer<Booking> onExpired) {
        int expired = 0;
        for (Booking booking : bookings) {
            try {
                booking.changeStatus(BookingStatus.EXPIRED, BookingStatusChange.SYSTEM, reason, now);
                Booking saved = bookingRepository.save(booking);
                expired++;
                onExpired.accept(saved == null ? booking : saved);
            } catch (OptimisticLockingFailureException | ResourceConflictException e) {
                log.info("event=BOOKING_EXPIRY_SKIPPED bookingId={} cause={}", booking.getId(),
                        e.getClass().getSimpleName());
            }
        }
        return expired;
    }
}

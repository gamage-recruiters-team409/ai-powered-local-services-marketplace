package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingDeclineReason;
import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleRequest;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationReason;
import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;
import com.gamagerecruiters.marketplace.modules.notification.service.NotificationEvent;
import com.gamagerecruiters.marketplace.modules.notification.service.NotificationService;

/**
 * Sends WP06 notifications for the booking status changes WP05 owns.
 * <ul>
 *   <li>Data uses only WP06's allowed keys: reference, slotStart, bookingStatus, district, reason
 *       (a {@link NotificationReason} code). Never the street address, notes, phone or email.</li>
 *   <li>Called only after the booking was saved. If a transaction is open, the notification waits until it
 *       commits, so a rolled-back change never notifies anyone.</li>
 *   <li>Best effort: a notification problem never fails or rolls back the booking action.</li>
 * </ul>
 */
@Component
public class BookingNotifier {

    private static final Logger log = LoggerFactory.getLogger(BookingNotifier.class);

    private final NotificationService notificationService;

    public BookingNotifier(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * A notifier that sends nothing. Only for code that uses the booking facade without the
     * notification module (e.g. other modules' tests written before booking notifications existed).
     */
    public static BookingNotifier silent() {
        return new BookingNotifier(null);
    }

    /** New request → provider. */
    public void requestReceived(Booking booking) {
        send(NotificationType.BOOKING_REQUEST_RECEIVED, booking.getProviderId(), booking, null);
    }

    /** Provider accepted (now awaiting payment) → customer. */
    public void accepted(Booking booking) {
        send(NotificationType.BOOKING_ACCEPTED, booking.getCustomerId(), booking, null);
    }

    /** Provider declined → customer, with the reason as a WP06 code. */
    public void declined(Booking booking, BookingDeclineReason reason) {
        send(NotificationType.BOOKING_DECLINED, booking.getCustomerId(), booking, toNotificationReason(reason));
    }

    /** Provider did not answer in time: customer gets BOOKING_EXPIRED, provider BOOKING_AUTO_DECLINED. */
    public void requestExpired(Booking booking) {
        send(NotificationType.BOOKING_EXPIRED, booking.getCustomerId(), booking, NotificationReason.NO_RESPONSE);
        send(NotificationType.BOOKING_AUTO_DECLINED, booking.getProviderId(), booking, NotificationReason.NO_RESPONSE);
    }

    /** Payment not completed in time → both parties (slot released). */
    public void paymentExpired(Booking booking) {
        send(NotificationType.BOOKING_EXPIRED, booking.getCustomerId(), booking, NotificationReason.PAYMENT_ISSUE);
        send(NotificationType.BOOKING_EXPIRED, booking.getProviderId(), booking, NotificationReason.PAYMENT_ISSUE);
    }

    /** Payment confirmed → both parties. */
    public void confirmed(Booking booking) {
        send(NotificationType.BOOKING_CONFIRMED, booking.getCustomerId(), booking, null);
        send(NotificationType.BOOKING_CONFIRMED, booking.getProviderId(), booking, null);
    }

    /** New time proposed → the other party; slotStart is the PROPOSED time. */
    public void rescheduleRequested(Booking booking, RescheduleRequest request) {
        send(NotificationType.RESCHEDULE_REQUESTED, otherParty(booking, request), booking, null,
                request.getProposedSlotStart());
    }

    /** Approved → the party who asked; slotStart is the new (now current) time. */
    public void rescheduleApproved(Booking booking, RescheduleRequest request) {
        send(NotificationType.RESCHEDULE_APPROVED, request.getRequestedBy(), booking, null, booking.getSlotStart());
    }

    /** Rejected → the party who asked; the booking keeps its time. */
    public void rescheduleRejected(Booking booking, RescheduleRequest request) {
        send(NotificationType.RESCHEDULE_REJECTED, request.getRequestedBy(), booking, null, booking.getSlotStart());
    }

    /** Not answered in time → the party who asked; reason NO_RESPONSE. */
    public void rescheduleExpired(Booking booking, RescheduleRequest request) {
        send(NotificationType.RESCHEDULE_EXPIRED, request.getRequestedBy(), booking, NotificationReason.NO_RESPONSE,
                booking.getSlotStart());
    }

    private static String otherParty(Booking booking, RescheduleRequest request) {
        return request.getRequestedBy() != null && request.getRequestedBy().equals(booking.getCustomerId())
                ? booking.getProviderId() : booking.getCustomerId();
    }

    static NotificationReason toNotificationReason(BookingDeclineReason reason) {
        if (reason == null) {
            return NotificationReason.OTHER;
        }
        return switch (reason) {
            case NOT_AVAILABLE, TOO_SHORT_NOTICE -> NotificationReason.PROVIDER_UNAVAILABLE;
            case OUTSIDE_SERVICE_AREA -> NotificationReason.OUTSIDE_SERVICE_AREA;
            case JOB_NOT_SUITABLE, OTHER -> NotificationReason.OTHER;
        };
    }

    static Map<String, String> data(Booking booking, NotificationReason reason) {
        return data(booking, reason, booking.getSlotStart());
    }

    static Map<String, String> data(Booking booking, NotificationReason reason, Instant slotStart) {
        Map<String, String> data = new LinkedHashMap<>();
        if (booking.getRef() != null) {
            data.put("reference", booking.getRef());
        }
        if (slotStart != null) {
            data.put("slotStart", slotStart.toString());
        }
        if (booking.getStatus() != null) {
            data.put("bookingStatus", booking.getStatus().name());
        }
        // District only: WP06 allows nothing more precise before confirmation, and we never send more after.
        if (booking.getAddress() != null && booking.getAddress().getDistrict() != null) {
            data.put("district", booking.getAddress().getDistrict());
        }
        if (reason != null) {
            data.put("reason", reason.name());
        }
        return data;
    }

    private void send(NotificationType type, String recipientUserId, Booking booking, NotificationReason reason) {
        send(type, recipientUserId, booking, reason, booking.getSlotStart());
    }

    private void send(NotificationType type, String recipientUserId, Booking booking, NotificationReason reason,
                      Instant slotStart) {
        if (recipientUserId == null || recipientUserId.isBlank()) {
            return;
        }
        NotificationEvent event = new NotificationEvent(type, recipientUserId, booking.getId(),
                data(booking, reason, slotStart));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(event);
                }
            });
        } else {
            deliver(event);
        }
    }

    private void deliver(NotificationEvent event) {
        if (notificationService == null) {
            return; // silent notifier
        }
        try {
            notificationService.notify(event);
        } catch (RuntimeException e) {
            log.warn("event=BOOKING_NOTIFICATION_FAILED type={} bookingId={} cause={}", event.type(),
                    event.bookingId(), e.getClass().getSimpleName());
        }
    }
}

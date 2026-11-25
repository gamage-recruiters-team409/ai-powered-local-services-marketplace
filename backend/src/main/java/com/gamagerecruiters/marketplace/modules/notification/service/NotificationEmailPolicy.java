package com.gamagerecruiters.marketplace.modules.notification.service;

import java.util.EnumSet;
import java.util.Set;

import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;

/**
 * Reminders and invitations follow user preferences, which are not built yet, so they are in-app
 * only for now; mandatory payment, dispute and security types are always allowed. Everything else
 * is in-app only.
 */
public final class NotificationEmailPolicy {
    private static final Set<NotificationType> ALLOWED = EnumSet.of(
            NotificationType.BOOKING_REQUEST_RECEIVED, NotificationType.BOOKING_WITHDRAWN,
            NotificationType.BOOKING_AUTO_DECLINED, NotificationType.BOOKING_ACCEPTED,
            NotificationType.BOOKING_DECLINED, NotificationType.BOOKING_EXPIRED,
            NotificationType.BOOKING_CONFIRMED, NotificationType.BOOKING_CANCELLED,
            NotificationType.RESCHEDULE_REQUESTED, NotificationType.RESCHEDULE_APPROVED,
            NotificationType.RESCHEDULE_REJECTED, NotificationType.RESCHEDULE_EXPIRED,
            NotificationType.CANCELLATION_PENALTY_APPLIED, NotificationType.PAYMENT_SUCCESSFUL,
            NotificationType.PAYMENT_FAILED, NotificationType.REFUND_PROCESSED,
            NotificationType.PAYOUT_PAID, NotificationType.PAYOUT_FAILED,
            NotificationType.DISPUTE_OUTCOME, NotificationType.VERIFICATION_APPROVED,
            NotificationType.VERIFICATION_REJECTED,
            NotificationType.VERIFICATION_RESUBMISSION_REQUESTED,
            NotificationType.VERIFICATION_EXPIRED);
    private NotificationEmailPolicy() { }
    public static boolean allows(NotificationType type) { return ALLOWED.contains(type); }
}

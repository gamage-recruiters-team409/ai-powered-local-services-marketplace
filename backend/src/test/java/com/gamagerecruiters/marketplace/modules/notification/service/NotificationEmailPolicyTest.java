package com.gamagerecruiters.marketplace.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.notification.domain.NotificationType;

class NotificationEmailPolicyTest {

    @Test
    void enablesExactlyTheTwentyThreeApprovedEmailTypes() {
        Set<NotificationType> expected = EnumSet.of(
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

        for (NotificationType type : NotificationType.values()) {
            assertThat(NotificationEmailPolicy.allows(type)).isEqualTo(expected.contains(type));
        }
    }
}

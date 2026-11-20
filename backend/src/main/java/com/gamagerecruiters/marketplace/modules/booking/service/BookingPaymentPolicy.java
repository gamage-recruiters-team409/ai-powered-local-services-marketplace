package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Payment timing after the provider accepts (aligned with WP07):
 * payment is due {@code booking.payment.window-minutes} (15) after acceptance; the booking expires
 * only after a further {@code booking.payment.expiry-grace-minutes} (15), so a payment WP07 has already
 * started can still complete.
 */
@Component
public class BookingPaymentPolicy {

    private final Duration paymentWindow;
    private final Duration expiryGrace;

    public BookingPaymentPolicy(@Value("${booking.payment.window-minutes:15}") long windowMinutes,
                                @Value("${booking.payment.expiry-grace-minutes:15}") long graceMinutes) {
        if (windowMinutes < 1 || graceMinutes < 0) {
            throw new IllegalArgumentException("booking.payment window must be >= 1 and grace >= 0 minutes");
        }
        this.paymentWindow = Duration.ofMinutes(windowMinutes);
        this.expiryGrace = Duration.ofMinutes(graceMinutes);
    }

    public Instant paymentDueAt(Instant acceptedAt) {
        return acceptedAt.plus(paymentWindow);
    }

    /** A booking whose payment was due before this instant can be expired. */
    public Instant expireDueBefore(Instant now) {
        return now.minus(expiryGrace);
    }
}

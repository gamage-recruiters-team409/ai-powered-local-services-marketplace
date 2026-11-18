
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the payment initiation deadline and verification grace rules.
 *
 * <p>
 * These tests use fixed UTC timestamps to make results deterministic.
 * They do not require Spring Boot, MongoDB, or a payment gateway.
 *
 * <p>
 * Current business-rule assumptions:
 * <ul>
 * <li>Payment initiation is allowed up to and including the 24-hour
 * deadline.</li>
 * <li>Eligible initiated payments may be verified up to and including
 * 15 minutes after the original payment deadline.</li>
 * <li>Late gateway success does not automatically authorize booking
 * reactivation.</li>
 * </ul>
 *
 * <p>
 * The exact boundary policy must be confirmed with the Booking
 * module owner and Team Lead before production integration.
 */
class PaymentDeadlineServiceTest {

    private PaymentDeadlineService deadlineService;

    /** Fixed provider acceptance time used throughout the test suite. */
    private static final Instant ACCEPTED_AT = Instant.parse("2026-11-11T10:00:00Z");

    /** Exactly 24 hours after provider acceptance. */
    private static final Instant PAYMENT_DEADLINE = Instant.parse("2026-11-12T10:00:00Z");

    /** Exactly 15 minutes after the payment deadline. */
    private static final Instant VERIFICATION_DEADLINE = Instant.parse("2026-11-12T10:15:00Z");

    @BeforeEach
    void setUp() {
        deadlineService = new PaymentDeadlineService();
    }

    @Test
    @DisplayName("Calculates the payment deadline 24 hours after provider acceptance")
    void calculatesPaymentDeadline() {
        Instant result = deadlineService.calculatePaymentDeadline(ACCEPTED_AT);

        assertEquals(PAYMENT_DEADLINE, result);
    }

    @Test
    @DisplayName("Calculates the verification deadline 15 minutes after payment deadline")
    void calculatesVerificationDeadline() {
        Instant result = deadlineService.calculateVerificationDeadline(ACCEPTED_AT);

        assertEquals(VERIFICATION_DEADLINE, result);
    }

    @Test
    @DisplayName("Allows payment initiation within the 24-hour window")
    void allowsPaymentInitiationWithinWindow() {
        Instant initiatedAt = Instant.parse("2026-11-12T09:58:00Z");

        assertTrue(deadlineService.isPaymentInitiationAllowed(
                ACCEPTED_AT, initiatedAt));
    }

    @Test
    @DisplayName("Allows payment initiation exactly at the 24-hour deadline")
    void allowsPaymentInitiationAtExactDeadline() {
        assertTrue(deadlineService.isPaymentInitiationAllowed(
                ACCEPTED_AT, PAYMENT_DEADLINE));
    }

    @Test
    @DisplayName("Rejects payment initiation after the 24-hour deadline")
    void rejectsLatePaymentInitiation() {
        Instant initiatedAt = PAYMENT_DEADLINE.plusSeconds(1);

        assertFalse(deadlineService.isPaymentInitiationAllowed(
                ACCEPTED_AT, initiatedAt));
    }

    @Test
    @DisplayName("Rejects payment initiation before provider acceptance")
    void rejectsInitiationBeforeAcceptance() {
        Instant initiatedAt = ACCEPTED_AT.minusSeconds(1);

        assertFalse(deadlineService.isPaymentInitiationAllowed(
                ACCEPTED_AT, initiatedAt));
    }

    @Test
    @DisplayName("Allows initiation exactly at provider acceptance")
    void allowsInitiationAtAcceptance() {
        assertTrue(deadlineService.isPaymentInitiationAllowed(
                ACCEPTED_AT, ACCEPTED_AT));
    }

    @Test
    @DisplayName("Provides a meaningful error when payment initiation is late")
    void providesMeaningfulLateInitiationError() {
        Instant initiatedAt = PAYMENT_DEADLINE.plusSeconds(300);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> deadlineService.validatePaymentInitiation(
                        ACCEPTED_AT, initiatedAt));

        assertTrue(exception.getMessage().contains(
                "24-hour payment deadline has expired"));
        assertTrue(exception.getMessage().contains(
                PAYMENT_DEADLINE.toString()));
        assertTrue(exception.getMessage().contains(
                initiatedAt.toString()));
    }

    @Test
    @DisplayName("Provides a meaningful error for initiation before acceptance")
    void providesMeaningfulEarlyInitiationError() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> deadlineService.validatePaymentInitiation(
                        ACCEPTED_AT, ACCEPTED_AT.minusSeconds(1)));

        assertTrue(exception.getMessage().contains(
                "before provider acceptance"));
    }

    @Test
    @DisplayName("Accepts valid payment initiation without throwing an exception")
    void validatesSuccessfulInitiation() {
        Instant initiatedAt = PAYMENT_DEADLINE.minusSeconds(60);

        deadlineService.validatePaymentInitiation(
                ACCEPTED_AT, initiatedAt);
    }

    @Test
    @DisplayName("Allows gateway verification before the original payment deadline")
    void allowsVerificationBeforePaymentDeadline() {
        Instant initiatedAt = ACCEPTED_AT.plusSeconds(60);
        Instant verifiedAt = PAYMENT_DEADLINE.minusSeconds(60);

        assertTrue(deadlineService.isPaymentVerificationAllowed(
                ACCEPTED_AT, initiatedAt, verifiedAt));
    }

    @Test
    @DisplayName("Allows gateway verification during the 15-minute grace period")
    void allowsVerificationDuringGracePeriod() {
        Instant initiatedAt = PAYMENT_DEADLINE.minusSeconds(120);
        Instant verifiedAt = PAYMENT_DEADLINE.plusSeconds(13 * 60);

        assertTrue(deadlineService.isPaymentVerificationAllowed(
                ACCEPTED_AT, initiatedAt, verifiedAt));
    }

    @Test
    @DisplayName("Allows gateway verification exactly at the grace deadline")
    void allowsVerificationAtExactGraceDeadline() {
        Instant initiatedAt = PAYMENT_DEADLINE.minusSeconds(120);

        assertTrue(deadlineService.isPaymentVerificationAllowed(
                ACCEPTED_AT, initiatedAt, VERIFICATION_DEADLINE));
    }

    @Test
    @DisplayName("Rejects gateway verification after the 15-minute grace deadline")
    void rejectsVerificationAfterGraceDeadline() {
        Instant initiatedAt = PAYMENT_DEADLINE.minusSeconds(120);
        Instant verifiedAt = VERIFICATION_DEADLINE.plusSeconds(1);

        assertFalse(deadlineService.isPaymentVerificationAllowed(
                ACCEPTED_AT, initiatedAt, verifiedAt));
    }

    @Test
    @DisplayName("Rejects verification when payment was initiated after the deadline")
    void rejectsVerificationForLateInitiation() {
        Instant initiatedAt = PAYMENT_DEADLINE.plusSeconds(1);
        Instant verifiedAt = PAYMENT_DEADLINE.plusSeconds(120);

        assertFalse(deadlineService.isPaymentVerificationAllowed(
                ACCEPTED_AT, initiatedAt, verifiedAt));
    }

    @Test
    @DisplayName("Rejects gateway verification before payment initiation")
    void rejectsVerificationBeforeInitiation() {
        Instant initiatedAt = PAYMENT_DEADLINE.minusSeconds(120);
        Instant verifiedAt = initiatedAt.minusSeconds(1);

        assertFalse(deadlineService.isPaymentVerificationAllowed(
                ACCEPTED_AT, initiatedAt, verifiedAt));
    }

    @Test
    @DisplayName("Provides reconciliation guidance for late gateway verification")
    void providesMeaningfulLateVerificationError() {
        Instant initiatedAt = PAYMENT_DEADLINE.minusSeconds(120);
        Instant verifiedAt = VERIFICATION_DEADLINE.plusSeconds(60);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> deadlineService.validatePaymentVerification(
                        ACCEPTED_AT, initiatedAt, verifiedAt));

        assertTrue(exception.getMessage().contains(
                "15-minute verification grace period has expired"));
        assertTrue(exception.getMessage().contains(
                "reconciliation, refund, or void"));
        assertTrue(exception.getMessage().contains(
                "must not be automatically reactivated"));
        assertTrue(exception.getMessage().contains(
                VERIFICATION_DEADLINE.toString()));
    }

    @Test
    @DisplayName("Rejects verification before initiation with a meaningful error")
    void providesMeaningfulVerificationSequenceError() {
        Instant initiatedAt = ACCEPTED_AT.plusSeconds(120);
        Instant verifiedAt = initiatedAt.minusSeconds(1);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> deadlineService.validatePaymentVerification(
                        ACCEPTED_AT, initiatedAt, verifiedAt));

        assertTrue(exception.getMessage().contains(
                "earlier than payment initiation time"));
    }

    @Test
    @DisplayName("Valid verification passes without throwing an exception")
    void validatesSuccessfulVerification() {
        Instant initiatedAt = PAYMENT_DEADLINE.minusSeconds(120);
        Instant verifiedAt = PAYMENT_DEADLINE.plusSeconds(60);

        deadlineService.validatePaymentVerification(
                ACCEPTED_AT, initiatedAt, verifiedAt);
    }

    @Test
    @DisplayName("Payment window is not expired exactly at its deadline")
    void paymentWindowNotExpiredAtExactDeadline() {
        assertFalse(deadlineService.isPaymentWindowExpired(
                ACCEPTED_AT, PAYMENT_DEADLINE));
    }

    @Test
    @DisplayName("Payment window expires immediately after its deadline")
    void paymentWindowExpiresAfterDeadline() {
        assertTrue(deadlineService.isPaymentWindowExpired(
                ACCEPTED_AT, PAYMENT_DEADLINE.plusNanos(1)));
    }

    @Test
    @DisplayName("Verification grace is not expired exactly at its deadline")
    void verificationGraceNotExpiredAtExactDeadline() {
        assertFalse(deadlineService.isVerificationGraceExpired(
                ACCEPTED_AT, VERIFICATION_DEADLINE));
    }

    @Test
    @DisplayName("Verification grace expires immediately after its deadline")
    void verificationGraceExpiresAfterDeadline() {
        assertTrue(deadlineService.isVerificationGraceExpired(
                ACCEPTED_AT, VERIFICATION_DEADLINE.plusNanos(1)));
    }

    @Test
    @DisplayName("Rejects a missing provider acceptance timestamp")
    void rejectsNullProviderAcceptance() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> deadlineService.calculatePaymentDeadline(null));

        assertEquals(
                "Provider acceptance timestamp is required and must not be null.",
                exception.getMessage());
    }

    @Test
    @DisplayName("Rejects a missing payment initiation timestamp")
    void rejectsNullPaymentInitiation() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> deadlineService.validatePaymentInitiation(
                        ACCEPTED_AT, null));

        assertEquals(
                "Payment initiation timestamp is required and must not be null.",
                exception.getMessage());
    }

    @Test
    @DisplayName("Rejects a missing gateway verification timestamp")
    void rejectsNullVerificationTimestamp() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> deadlineService.validatePaymentVerification(
                        ACCEPTED_AT, PAYMENT_DEADLINE, null));

        assertEquals(
                "Payment verification timestamp is required and must not be null.",
                exception.getMessage());
    }

    @Test
    @DisplayName("Rejects a missing current timestamp for expiry checks")
    void rejectsNullCurrentTimestamp() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> deadlineService.isPaymentWindowExpired(
                        ACCEPTED_AT, null));

        assertEquals(
                "Current timestamp is required and must not be null.",
                exception.getMessage());
    }

    @Test
    @DisplayName("Rejects an out-of-range timestamp when calculating the deadline")
    void rejectsTimestampOverflow() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> deadlineService.calculatePaymentDeadline(Instant.MAX));

        assertTrue(exception.getMessage().contains(
                "provider acceptance timestamp is out of range"));
        assertNotNull(exception.getCause());
    }

    @Test
    @DisplayName("Rejects an out-of-range timestamp when calculating grace deadline")
    void rejectsVerificationDeadlineOverflow() {
        Instant acceptedAt = Instant.MAX.minusSeconds(
                PaymentDeadlineService.PAYMENT_WINDOW.getSeconds());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> deadlineService.calculateVerificationDeadline(acceptedAt));

        assertTrue(exception.getMessage().contains(
                "verification deadline: timestamp is out of range"));
        assertNotNull(exception.getCause());
    }
}

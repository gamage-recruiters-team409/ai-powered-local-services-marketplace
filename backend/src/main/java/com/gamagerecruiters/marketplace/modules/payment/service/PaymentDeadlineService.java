
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Validates payment initiation and gateway verification deadlines.
 *
 * <p>
 * Business rules:
 * <ul>
 * <li>A customer must initiate payment within 24 hours of provider
 * acceptance.</li>
 * <li>A payment initiated before the deadline may receive up to 15 additional
 * minutes for gateway verification.</li>
 * <li>Payments initiated after the deadline are not eligible for verification
 * grace.</li>
 * <li>A late gateway success must not automatically reactivate an expired
 * booking.</li>
 * </ul>
 *
 * <p>
 * This service only evaluates deadlines. It does not update booking statuses,
 * persist payments, or communicate with a payment gateway.
 *
 * <p>
 * The 15-minute grace period is currently interpreted as extending the
 * original 24-hour deadline, not as 15 minutes after each payment initiation.
 * This boundary rule must be confirmed with the Booking module owner and TL.
 */
public class PaymentDeadlineService {

    /** Maximum time allowed to initiate payment after provider acceptance. */
    public static final Duration PAYMENT_WINDOW = Duration.ofHours(24);

    /** Additional gateway verification time for eligible initiated payments. */
    public static final Duration VERIFICATION_GRACE = Duration.ofMinutes(15);

    /**
     * Calculates the payment initiation deadline.
     *
     * @param providerAcceptedAt the UTC timestamp of provider acceptance
     * @return the payment initiation deadline
     * @throws IllegalArgumentException if the timestamp is missing or invalid
     */
    public Instant calculatePaymentDeadline(Instant providerAcceptedAt) {
        requireTimestamp(providerAcceptedAt, "Provider acceptance timestamp");

        try {
            return providerAcceptedAt.plus(PAYMENT_WINDOW);
        } catch (ArithmeticException | java.time.DateTimeException exception) {
            throw new IllegalArgumentException(
                    "Unable to calculate payment deadline: provider acceptance timestamp is out of range.",
                    exception);
        }
    }

    /**
     * Calculates the maximum verification deadline for an eligible payment.
     *
     * @param providerAcceptedAt the UTC provider acceptance timestamp
     * @return the latest allowed gateway verification timestamp
     */
    public Instant calculateVerificationDeadline(Instant providerAcceptedAt) {
        Instant paymentDeadline = calculatePaymentDeadline(providerAcceptedAt);

        try {
            return paymentDeadline.plus(VERIFICATION_GRACE);
        } catch (ArithmeticException | java.time.DateTimeException exception) {
            throw new IllegalArgumentException(
                    "Unable to calculate verification deadline: timestamp is out of range.",
                    exception);
        }
    }

    /**
     * Checks whether a payment was initiated within the allowed 24-hour window.
     *
     * <p>
     * Initiation exactly at the deadline is allowed. Initiation before
     * provider acceptance is rejected.
     *
     * @param providerAcceptedAt provider acceptance timestamp
     * @param paymentInitiatedAt actual payment initiation timestamp
     * @return true when initiation is within the allowed window
     */
    public boolean isPaymentInitiationAllowed(
            Instant providerAcceptedAt,
            Instant paymentInitiatedAt) {

        requireTimestamp(providerAcceptedAt, "Provider acceptance timestamp");
        requireTimestamp(paymentInitiatedAt, "Payment initiation timestamp");

        Instant deadline = calculatePaymentDeadline(providerAcceptedAt);

        return !paymentInitiatedAt.isBefore(providerAcceptedAt)
                && !paymentInitiatedAt.isAfter(deadline);
    }

    /**
     * Validates payment initiation and provides a meaningful error message
     * when the payment is outside the permitted window.
     *
     * @param providerAcceptedAt provider acceptance timestamp
     * @param paymentInitiatedAt payment initiation timestamp
     * @throws IllegalArgumentException if initiation is invalid or late
     */
    public void validatePaymentInitiation(
            Instant providerAcceptedAt,
            Instant paymentInitiatedAt) {

        requireTimestamp(providerAcceptedAt, "Provider acceptance timestamp");
        requireTimestamp(paymentInitiatedAt, "Payment initiation timestamp");

        if (paymentInitiatedAt.isBefore(providerAcceptedAt)) {
            throw new IllegalArgumentException(
                    "Payment initiation rejected: payment cannot be initiated before provider acceptance.");
        }

        Instant deadline = calculatePaymentDeadline(providerAcceptedAt);

        if (paymentInitiatedAt.isAfter(deadline)) {
            throw new IllegalArgumentException(
                    "Payment initiation rejected: the 24-hour payment deadline has expired. "
                            + "Payment deadline: " + deadline
                            + ". Payment initiation time: " + paymentInitiatedAt + ".");
        }
    }

    /**
     * Checks whether gateway verification was completed within the permitted
     * verification period.
     *
     * <p>
     * A payment must first be initiated within the 24-hour payment window.
     * Verification cannot occur before payment initiation.
     *
     * @param providerAcceptedAt provider acceptance timestamp
     * @param paymentInitiatedAt payment initiation timestamp
     * @param paymentVerifiedAt  verified gateway success timestamp
     * @return true if payment verification is eligible for confirmation
     */
    public boolean isPaymentVerificationAllowed(
            Instant providerAcceptedAt,
            Instant paymentInitiatedAt,
            Instant paymentVerifiedAt) {

        requireTimestamp(providerAcceptedAt, "Provider acceptance timestamp");
        requireTimestamp(paymentInitiatedAt, "Payment initiation timestamp");
        requireTimestamp(paymentVerifiedAt, "Payment verification timestamp");

        if (!isPaymentInitiationAllowed(providerAcceptedAt, paymentInitiatedAt)) {
            return false;
        }

        if (paymentVerifiedAt.isBefore(paymentInitiatedAt)) {
            return false;
        }

        Instant verificationDeadline = calculateVerificationDeadline(providerAcceptedAt);

        return !paymentVerifiedAt.isAfter(verificationDeadline);
    }

    /**
     * Validates gateway verification before the caller considers confirming
     * the booking.
     *
     * <p>
     * Successful deadline validation alone does not authorize booking
     * confirmation. The caller must additionally verify the gateway signature,
     * payment amount, currency, transaction identity, and current booking state.
     *
     * @param providerAcceptedAt provider acceptance timestamp
     * @param paymentInitiatedAt payment initiation timestamp
     * @param paymentVerifiedAt  gateway verification timestamp
     * @throws IllegalArgumentException when verification is invalid or late
     */
    public void validatePaymentVerification(
            Instant providerAcceptedAt,
            Instant paymentInitiatedAt,
            Instant paymentVerifiedAt) {

        requireTimestamp(providerAcceptedAt, "Provider acceptance timestamp");
        requireTimestamp(paymentInitiatedAt, "Payment initiation timestamp");
        requireTimestamp(paymentVerifiedAt, "Payment verification timestamp");

        // Reject late initiation before considering the verification grace.
        validatePaymentInitiation(providerAcceptedAt, paymentInitiatedAt);

        if (paymentVerifiedAt.isBefore(paymentInitiatedAt)) {
            throw new IllegalArgumentException(
                    "Payment verification rejected: verification time cannot be earlier "
                            + "than payment initiation time.");
        }

        Instant verificationDeadline = calculateVerificationDeadline(providerAcceptedAt);

        if (paymentVerifiedAt.isAfter(verificationDeadline)) {
            throw new IllegalArgumentException(
                    "Payment verification rejected: the 15-minute verification grace period "
                            + "has expired. Verification deadline: " + verificationDeadline
                            + ". Actual verification time: " + paymentVerifiedAt
                            + ". Late gateway success requires reconciliation, refund, "
                            + "or void processing; the booking must not be automatically reactivated.");
        }
    }

    /**
     * Determines whether the payment initiation window has expired.
     *
     * <p>
     * At the exact deadline the window is still valid. It is considered
     * expired only after that timestamp.
     *
     * @param providerAcceptedAt provider acceptance timestamp
     * @param currentTime        trusted current timestamp
     * @return true if the initiation deadline has passed
     */
    public boolean isPaymentWindowExpired(
            Instant providerAcceptedAt,
            Instant currentTime) {

        requireTimestamp(currentTime, "Current timestamp");

        return currentTime.isAfter(calculatePaymentDeadline(providerAcceptedAt));
    }

    /**
     * Determines whether the maximum verification grace deadline has passed.
     *
     * <p>
     * This method does not decide whether a booking should expire.
     * That decision also depends on whether a payment was initiated and
     * verified, as well as the current booking status.
     *
     * @param providerAcceptedAt provider acceptance timestamp
     * @param currentTime        trusted current timestamp
     * @return true when the maximum verification deadline has passed
     */
    public boolean isVerificationGraceExpired(
            Instant providerAcceptedAt,
            Instant currentTime) {

        requireTimestamp(currentTime, "Current timestamp");

        return currentTime.isAfter(calculateVerificationDeadline(providerAcceptedAt));
    }

    /**
     * Rejects missing timestamps with a descriptive validation message.
     *
     * @param timestamp timestamp to validate
     * @param fieldName human-readable field name
     */
    private void requireTimestamp(Instant timestamp, String fieldName) {
        Objects.requireNonNull(fieldName, "Field name must not be null.");

        if (timestamp == null) {
            throw new IllegalArgumentException(
                    fieldName + " is required and must not be null.");
        }
    }
}

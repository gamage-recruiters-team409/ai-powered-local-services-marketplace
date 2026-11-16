
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Validates allowed transitions between payment lifecycle statuses.
 *
 * <p>
 * This class enforces a proposed payment state machine. It does not
 * perform gateway operations, update MongoDB documents, or transfer funds.
 *
 * <p>
 * Important integration requirements:
 * <ul>
 * <li>Gateway events must be authenticated and processed idempotently.</li>
 * <li>Database updates must prevent concurrent or duplicate transitions.</li>
 * <li>Refund amounts must be validated separately against the captured
 * amount and previously refunded amount.</li>
 * <li>Payment release must be authorized by the completion and dispute
 * workflows, not merely by a valid status transition.</li>
 * </ul>
 *
 * <p>
 * The transition policy must be reviewed with the Team Lead before
 * production gateway integration.
 */
public class PaymentStatusTransitionService {

    /**
     * Allowed next statuses for each payment status.
     *
     * <p>
     * EnumMap and EnumSet provide type-safe, efficient enum lookups.
     * Terminal states intentionally have no outgoing transitions.
     */
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = buildAllowedTransitions();

    /**
     * Creates the transition rules in one central location.
     *
     * @return an immutable mapping of payment statuses to allowed next states
     */
    private static Map<PaymentStatus, Set<PaymentStatus>> buildAllowedTransitions() {

        EnumMap<PaymentStatus, Set<PaymentStatus>> transitions = new EnumMap<>(PaymentStatus.class);

        transitions.put(
                PaymentStatus.INITIATED,
                Set.of(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED));

        transitions.put(
                PaymentStatus.AUTHORIZED,
                Set.of(PaymentStatus.CAPTURED, PaymentStatus.FAILED));

        transitions.put(
                PaymentStatus.CAPTURED,
                Set.of(PaymentStatus.RELEASED, PaymentStatus.REFUND_PENDING));

        transitions.put(
                PaymentStatus.REFUND_PENDING,
                Set.of(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED));

        transitions.put(
                PaymentStatus.PARTIALLY_REFUNDED,
                Set.of(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED));

        // Terminal statuses cannot transition to another state.
        transitions.put(PaymentStatus.RELEASED, Set.of());
        transitions.put(PaymentStatus.REFUNDED, Set.of());
        transitions.put(PaymentStatus.FAILED, Set.of());

        return Map.copyOf(transitions);
    }

    /**
     * Checks whether a payment status transition is allowed.
     *
     * <p>
     * Null statuses, unchanged statuses, and transitions from terminal
     * statuses are rejected.
     *
     * @param currentStatus the payment's current status
     * @param targetStatus  the requested next status
     * @return true if the transition is allowed, otherwise false
     */
    public boolean isTransitionAllowed(
            PaymentStatus currentStatus,
            PaymentStatus targetStatus) {

        if (currentStatus == null || targetStatus == null) {
            return false;
        }

        if (currentStatus == targetStatus) {
            return false;
        }

        return ALLOWED_TRANSITIONS
                .getOrDefault(currentStatus, Set.of())
                .contains(targetStatus);
    }

    /**
     * Validates a payment status transition.
     *
     * <p>
     * Provides a descriptive error message when a transition is rejected.
     * A successful validation only confirms that the requested status change
     * is structurally allowed by this state machine.
     *
     * @param currentStatus the payment's current status
     * @param targetStatus  the requested next status
     * @throws IllegalArgumentException if either status is missing
     * @throws IllegalStateException    if the transition is not allowed
     */
    public void validateTransition(
            PaymentStatus currentStatus,
            PaymentStatus targetStatus) {

        if (currentStatus == null) {
            throw new IllegalArgumentException(
                    "Payment status transition rejected: current status is required.");
        }

        if (targetStatus == null) {
            throw new IllegalArgumentException(
                    "Payment status transition rejected: target status is required.");
        }

        if (currentStatus == targetStatus) {
            throw new IllegalStateException(
                    "Payment status transition rejected: payment is already in status "
                            + currentStatus
                            + ". Duplicate status transitions are not allowed.");
        }

        if (isTerminalStatus(currentStatus)) {
            throw new IllegalStateException(
                    "Payment status transition rejected: payment status "
                            + currentStatus
                            + " is terminal and cannot transition to "
                            + targetStatus + ".");
        }

        if (!isTransitionAllowed(currentStatus, targetStatus)) {
            throw new IllegalStateException(
                    "Payment status transition rejected: transition from "
                            + currentStatus + " to " + targetStatus
                            + " is not allowed. Allowed next statuses: "
                            + getAllowedNextStatuses(currentStatus) + ".");
        }
    }

    /**
     * Returns the statuses that may follow the given payment status.
     *
     * @param currentStatus the payment's current status
     * @return an immutable set of allowed next statuses
     * @throws IllegalArgumentException if currentStatus is null
     */
    public Set<PaymentStatus> getAllowedNextStatuses(
            PaymentStatus currentStatus) {

        Objects.requireNonNull(
                currentStatus,
                "Current payment status is required to retrieve allowed transitions.");

        return ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of());
    }

    /**
     * Determines whether a payment status is terminal.
     *
     * <p>
     * Terminal statuses cannot be changed using the current state
     * machine. Any exceptional reconciliation must be handled through
     * a separately reviewed workflow with an audit trail.
     *
     * @param status the payment status to inspect
     * @return true if the status is terminal
     * @throws IllegalArgumentException if status is null
     */
    public boolean isTerminalStatus(PaymentStatus status) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "Payment status is required to determine whether it is terminal.");
        }

        return status == PaymentStatus.RELEASED
                || status == PaymentStatus.REFUNDED
                || status == PaymentStatus.FAILED;
    }

    /**
     * Determines whether a payment is in a refund-related state.
     *
     * @param status the payment status to inspect
     * @return true for pending, partial, or completed refund statuses
     * @throws IllegalArgumentException if status is null
     */
    public boolean isRefundStatus(PaymentStatus status) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "Payment status is required to determine refund state.");
        }

        return status == PaymentStatus.REFUND_PENDING
                || status == PaymentStatus.PARTIALLY_REFUNDED
                || status == PaymentStatus.REFUNDED;
    }
}

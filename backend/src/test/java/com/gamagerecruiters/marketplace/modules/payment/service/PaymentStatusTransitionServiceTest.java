
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Unit tests for payment lifecycle status transition validation.
 *
 * <p>
 * These tests verify the proposed payment state machine, including
 * valid transitions, rejected transitions, terminal states, duplicate
 * requests, refund classification, and descriptive validation errors.
 *
 * <p>
 * No MongoDB connection, Spring application context, or payment gateway
 * is required because the transition validator is a pure Java class.
 *
 * <p>
 * Passing these tests does not prove that gateway operations are
 * idempotent or that concurrent database updates are safe.
 */
class PaymentStatusTransitionServiceTest {

    private PaymentStatusTransitionService transitionService;

    @BeforeEach
    void setUp() {
        transitionService = new PaymentStatusTransitionService();
    }

    @Test
    @DisplayName("Allows INITIATED to AUTHORIZED")
    void allowsInitiatedToAuthorized() {
        assertTrue(transitionService.isTransitionAllowed(
                PaymentStatus.INITIATED, PaymentStatus.AUTHORIZED));
    }

    @Test
    @DisplayName("Allows INITIATED to FAILED")
    void allowsInitiatedToFailed() {
        assertTrue(transitionService.isTransitionAllowed(
                PaymentStatus.INITIATED, PaymentStatus.FAILED));
    }

    @Test
    @DisplayName("Allows AUTHORIZED to CAPTURED")
    void allowsAuthorizedToCaptured() {
        assertTrue(transitionService.isTransitionAllowed(
                PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED));
    }

    @Test
    @DisplayName("Allows AUTHORIZED to FAILED")
    void allowsAuthorizedToFailed() {
        assertTrue(transitionService.isTransitionAllowed(
                PaymentStatus.AUTHORIZED, PaymentStatus.FAILED));
    }

    @Test
    @DisplayName("Allows CAPTURED to RELEASED")
    void allowsCapturedToReleased() {
        assertTrue(transitionService.isTransitionAllowed(
                PaymentStatus.CAPTURED, PaymentStatus.RELEASED));
    }

    @Test
    @DisplayName("Allows CAPTURED to REFUND_PENDING")
    void allowsCapturedToRefundPending() {
        assertTrue(transitionService.isTransitionAllowed(
                PaymentStatus.CAPTURED, PaymentStatus.REFUND_PENDING));
    }

    @Test
    @DisplayName("Allows REFUND_PENDING to PARTIALLY_REFUNDED")
    void allowsRefundPendingToPartiallyRefunded() {
        assertTrue(transitionService.isTransitionAllowed(
                PaymentStatus.REFUND_PENDING,
                PaymentStatus.PARTIALLY_REFUNDED));
    }

    @Test
    @DisplayName("Allows REFUND_PENDING to REFUNDED")
    void allowsRefundPendingToRefunded() {
        assertTrue(transitionService.isTransitionAllowed(
                PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("Allows PARTIALLY_REFUNDED to REFUND_PENDING")
    void allowsPartialRefundToAnotherRefundRequest() {
        assertTrue(transitionService.isTransitionAllowed(
                PaymentStatus.PARTIALLY_REFUNDED,
                PaymentStatus.REFUND_PENDING));
    }

    @Test
    @DisplayName("Allows PARTIALLY_REFUNDED to REFUNDED")
    void allowsPartialRefundToFullRefund() {
        assertTrue(transitionService.isTransitionAllowed(
                PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("Rejects INITIATED to RELEASED")
    void rejectsInitiatedToReleased() {
        assertFalse(transitionService.isTransitionAllowed(
                PaymentStatus.INITIATED, PaymentStatus.RELEASED));
    }

    @Test
    @DisplayName("Rejects INITIATED directly to CAPTURED")
    void rejectsInitiatedToCaptured() {
        assertFalse(transitionService.isTransitionAllowed(
                PaymentStatus.INITIATED, PaymentStatus.CAPTURED));
    }

    @Test
    @DisplayName("Rejects AUTHORIZED directly to REFUNDED")
    void rejectsAuthorizedToRefunded() {
        assertFalse(transitionService.isTransitionAllowed(
                PaymentStatus.AUTHORIZED, PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("Rejects CAPTURED returning to AUTHORIZED")
    void rejectsCapturedToAuthorized() {
        assertFalse(transitionService.isTransitionAllowed(
                PaymentStatus.CAPTURED, PaymentStatus.AUTHORIZED));
    }

    @Test
    @DisplayName("Rejects FAILED payment becoming CAPTURED")
    void rejectsFailedToCaptured() {
        assertFalse(transitionService.isTransitionAllowed(
                PaymentStatus.FAILED, PaymentStatus.CAPTURED));
    }

    @Test
    @DisplayName("Rejects REFUNDED payment becoming RELEASED")
    void rejectsRefundedToReleased() {
        assertFalse(transitionService.isTransitionAllowed(
                PaymentStatus.REFUNDED, PaymentStatus.RELEASED));
    }

    @Test
    @DisplayName("Rejects RELEASED payment entering refund processing")
    void rejectsReleasedToRefundPending() {
        assertFalse(transitionService.isTransitionAllowed(
                PaymentStatus.RELEASED, PaymentStatus.REFUND_PENDING));
    }

    @Test
    @DisplayName("Rejects duplicate CAPTURED to CAPTURED transition")
    void rejectsDuplicateStatusTransition() {
        assertFalse(transitionService.isTransitionAllowed(
                PaymentStatus.CAPTURED, PaymentStatus.CAPTURED));
    }

    @Test
    @DisplayName("Rejects null current status in boolean validation")
    void rejectsNullCurrentStatus() {
        assertFalse(transitionService.isTransitionAllowed(
                null, PaymentStatus.CAPTURED));
    }

    @Test
    @DisplayName("Rejects null target status in boolean validation")
    void rejectsNullTargetStatus() {
        assertFalse(transitionService.isTransitionAllowed(
                PaymentStatus.INITIATED, null));
    }

    @Test
    @DisplayName("Valid transition passes strict validation")
    void validatesAllowedTransition() {
        transitionService.validateTransition(
                PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("Strict validation explains invalid transition")
    void explainsInvalidTransition() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> transitionService.validateTransition(
                        PaymentStatus.INITIATED, PaymentStatus.RELEASED));

        assertTrue(exception.getMessage().contains(
                "transition from INITIATED to RELEASED is not allowed"));
        assertTrue(exception.getMessage().contains(
                "Allowed next statuses"));
        assertTrue(exception.getMessage().contains("AUTHORIZED"));
        assertTrue(exception.getMessage().contains("FAILED"));
    }

    @Test
    @DisplayName("Strict validation explains terminal status rejection")
    void explainsTerminalStatusRejection() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> transitionService.validateTransition(
                        PaymentStatus.FAILED, PaymentStatus.CAPTURED));

        assertTrue(exception.getMessage().contains(
                "FAILED is terminal"));
        assertTrue(exception.getMessage().contains(
                "cannot transition to CAPTURED"));
    }

    @Test
    @DisplayName("Strict validation explains duplicate status rejection")
    void explainsDuplicateStatusRejection() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> transitionService.validateTransition(
                        PaymentStatus.CAPTURED, PaymentStatus.CAPTURED));

        assertTrue(exception.getMessage().contains(
                "already in status CAPTURED"));
        assertTrue(exception.getMessage().contains(
                "Duplicate status transitions are not allowed"));
    }

    @Test
    @DisplayName("Strict validation rejects missing current status")
    void validatesMissingCurrentStatus() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transitionService.validateTransition(
                        null, PaymentStatus.CAPTURED));

        assertEquals(
                "Payment status transition rejected: current status is required.",
                exception.getMessage());
    }

    @Test
    @DisplayName("Strict validation rejects missing target status")
    void validatesMissingTargetStatus() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transitionService.validateTransition(
                        PaymentStatus.INITIATED, null));

        assertEquals(
                "Payment status transition rejected: target status is required.",
                exception.getMessage());
    }

    @Test
    @DisplayName("Returns correct next statuses for INITIATED")
    void returnsAllowedNextStatuses() {
        assertEquals(
                Set.of(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED),
                transitionService.getAllowedNextStatuses(
                        PaymentStatus.INITIATED));
    }

    @Test
    @DisplayName("Returned transition set cannot be modified")
    void returnsImmutableAllowedStatuses() {
        Set<PaymentStatus> allowed = transitionService.getAllowedNextStatuses(
                PaymentStatus.CAPTURED);

        assertThrows(
                UnsupportedOperationException.class,
                () -> allowed.add(PaymentStatus.FAILED));
    }

    @Test
    @DisplayName("All defined payment statuses have transition entries")
    void handlesEveryPaymentStatus() {
        for (PaymentStatus status : PaymentStatus.values()) {
            Set<PaymentStatus> allowed = transitionService.getAllowedNextStatuses(status);

            assertTrue(allowed != null);
        }
    }

    @Test
    @DisplayName("Rejects null when retrieving allowed statuses")
    void rejectsNullAllowedStatusLookup() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> transitionService.getAllowedNextStatuses(null));

        assertTrue(exception.getMessage().contains(
                "Current payment status is required"));
    }

    @Test
    @DisplayName("Identifies FAILED, REFUNDED, and RELEASED as terminal")
    void identifiesTerminalStatuses() {
        assertTrue(transitionService.isTerminalStatus(PaymentStatus.FAILED));
        assertTrue(transitionService.isTerminalStatus(PaymentStatus.REFUNDED));
        assertTrue(transitionService.isTerminalStatus(PaymentStatus.RELEASED));

        assertFalse(transitionService.isTerminalStatus(PaymentStatus.INITIATED));
        assertFalse(transitionService.isTerminalStatus(PaymentStatus.CAPTURED));
    }

    @Test
    @DisplayName("Rejects null terminal status validation")
    void rejectsNullTerminalStatus() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transitionService.isTerminalStatus(null));

        assertTrue(exception.getMessage().contains(
                "Payment status is required"));
    }

    @Test
    @DisplayName("Identifies refund-related statuses correctly")
    void identifiesRefundStatuses() {
        assertTrue(transitionService.isRefundStatus(
                PaymentStatus.REFUND_PENDING));
        assertTrue(transitionService.isRefundStatus(
                PaymentStatus.PARTIALLY_REFUNDED));
        assertTrue(transitionService.isRefundStatus(
                PaymentStatus.REFUNDED));

        assertFalse(transitionService.isRefundStatus(
                PaymentStatus.CAPTURED));
        assertFalse(transitionService.isRefundStatus(
                PaymentStatus.RELEASED));
    }

    @Test
    @DisplayName("Rejects null refund status validation")
    void rejectsNullRefundStatus() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transitionService.isRefundStatus(null));

        assertTrue(exception.getMessage().contains(
                "Payment status is required"));
    }

    @Test
    @DisplayName("Every allowed transition passes strict validation")
    void everyAllowedTransitionPassesValidation() {
        for (PaymentStatus current : PaymentStatus.values()) {
            for (PaymentStatus target : transitionService.getAllowedNextStatuses(current)) {

                assertTrue(transitionService.isTransitionAllowed(
                        current, target));

                transitionService.validateTransition(current, target);
            }
        }
    }

    @Test
    @DisplayName("Terminal statuses have no allowed outgoing transitions")
    void terminalStatusesHaveNoOutgoingTransitions() {
        assertTrue(transitionService.getAllowedNextStatuses(
                PaymentStatus.FAILED).isEmpty());

        assertTrue(transitionService.getAllowedNextStatuses(
                PaymentStatus.REFUNDED).isEmpty());

        assertTrue(transitionService.getAllowedNextStatuses(
                PaymentStatus.RELEASED).isEmpty());
    }
}

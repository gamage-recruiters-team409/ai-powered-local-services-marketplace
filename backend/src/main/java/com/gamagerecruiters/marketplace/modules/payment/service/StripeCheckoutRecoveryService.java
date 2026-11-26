package com.gamagerecruiters.marketplace.modules.payment.service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway.RecoveredCheckoutSession;

/**
 * Recovers existing checkout sessions for interrupted payment attempts.
 *
 * Recovery is read-only at the gateway and uses conditional local
 * persistence to prevent overwriting an existing session reference.
 *
 * This internal service must only be invoked through a trusted
 * operational recovery workflow.
 */
@Service
public class StripeCheckoutRecoveryService {

    private final PaymentPersistenceService persistenceService;
    private final PaymentCheckoutGateway checkoutGateway;
    private final StripeCheckoutReservationService reservationService;
    private final StripeCheckoutSessionPersistenceService sessionPersistenceService;

    public StripeCheckoutRecoveryService(
            PaymentPersistenceService persistenceService,
            PaymentCheckoutGateway checkoutGateway,
            StripeCheckoutReservationService reservationService,
            StripeCheckoutSessionPersistenceService sessionPersistenceService) {

        this.persistenceService = Objects.requireNonNull(persistenceService);
        this.checkoutGateway = Objects.requireNonNull(checkoutGateway);
        this.reservationService = Objects.requireNonNull(reservationService);
        this.sessionPersistenceService =
                Objects.requireNonNull(sessionPersistenceService);
    }

    /**
     * Attempts to recover a previously created checkout session.
     *
     * An unresolved gateway lookup never releases the reservation or creates
     * another session. Payment capture remains the responsibility of
     * the verified webhook processing workflow.
     *
     * @param paymentId persisted payment identifier
     * @return true when a session reference was attached or already exists
     */
    public boolean recoverCheckout(String paymentId) {

        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("Payment ID is required");
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Checkout recovery must run outside a database transaction");
        }

        Payment payment = persistenceService
                .findPaymentById(paymentId.trim())
                .orElseThrow(() -> new IllegalStateException(
                        "Payment not found for checkout recovery"));

        // Never overwrite an already attached session reference.
        if (payment.getGatewayRef() != null) {

            if (payment.getGatewayRef().isBlank()
                    || payment.getCheckoutReservationToken() != null
                    || payment.getCheckoutReservedAt() != null
                    || payment.getCheckoutSubmissionStarted() != null) {
                throw new IllegalStateException(
                        "Inconsistent checkout reservation state");
            }

            return true;
        }

        if (payment.getStatus() != PaymentStatus.INITIATED) {
            throw new IllegalStateException(
                    "Payment is not eligible for checkout recovery");
        }

        String reservationToken =
                payment.getCheckoutReservationToken();

        Instant reservedAt = payment.getCheckoutReservedAt();

        if (reservationToken == null
                || reservationToken.isBlank()
                || reservedAt == null
                || reservedAt.isAfter(Instant.now())) {

            throw new IllegalStateException(
                    "Valid checkout reservation is required for recovery");
        }

        if (payment.getGatewayPaymentIntentId() != null
                || payment.getAmount() == null
                || payment.getAmount().signum() <= 0
                || payment.getCurrency() == null
                || payment.getCurrency().isBlank()) {

            throw new IllegalStateException(
                    "Invalid payment state for checkout recovery");
        }

        // Only explicitly unsubmitted reservations may be released.
        // The conditional update also verifies the minimum reservation age.
        if (Boolean.FALSE.equals(payment.getCheckoutSubmissionStarted())) {
            reservationService.releaseStaleUnsubmittedReservation(
                    payment.getId(), reservationToken);

            // No session was attached. A subsequent authorized initiation
            // may acquire a new reservation if the release succeeded.
            return false;
        }

        // Perform the external lookup without holding a database transaction.
        Optional<RecoveredCheckoutSession> recovered =
                checkoutGateway.findCheckoutSession(
                        payment.getId(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        reservedAt);

        if (recovered == null) {
            throw new IllegalStateException(
                    "Invalid checkout recovery response");
        }

        if (recovered.isEmpty()) {
            // Absence of a session is not proof of non-submission.
            return false;
        }

        RecoveredCheckoutSession session = recovered.get();

        if (session.sessionId() == null
                || !session.sessionId().startsWith("cs_")) {
            throw new IllegalStateException(
                    "Invalid recovered checkout session reference");
        }

        boolean validOpenSession =
                "open".equals(session.status())
                        && "unpaid".equals(session.paymentStatus())
                        && session.checkoutUrl() != null
                        && !session.checkoutUrl().isBlank();

        boolean validCompletedSession =
                "complete".equals(session.status())
                        && ("paid".equals(session.paymentStatus())
                            || "unpaid".equals(session.paymentStatus()));

        if (!validOpenSession && !validCompletedSession) {
            throw new IllegalStateException(
                    "Recovered checkout session state is not supported");
        }

        // Existing atomic persistence verifies reservation ownership,
        // INITIATED status and the absence of a gateway reference.
        sessionPersistenceService.attachSession(
                payment.getId(),
                session.sessionId(),
                reservationToken);

        return true;
    }
}
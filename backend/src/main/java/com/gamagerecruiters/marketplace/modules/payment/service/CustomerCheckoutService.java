package com.gamagerecruiters.marketplace.modules.payment.service;

import java.time.Instant;
import java.util.Objects;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeCheckoutRedirectPolicy;
import com.gamagerecruiters.marketplace.modules.payment.config.StripeGatewayProperties;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;

/**
 * Coordinates secure customer Checkout initiation and recovery.
 *
 * <p>
 * Financial values are obtained exclusively from trusted booking
 * data. Existing payments and gateway sessions are reused without
 * creating replacement payment attempts.
 * </p>
 *
 * <p>
 * This service does not confirm payment capture or change booking
 * status. Those operations belong to verified payment processing.
 * </p>
 */
@Service
public class CustomerCheckoutService {

    private final BookingPaymentCreationService creationService;

    private final PaymentBookingAuthorizationService authorizationService;

    private final TrustedBookingPaymentQuoteProvider quoteProvider;

    private final StripeCheckoutInitiationService initiationService;

    private final StripeCheckoutRecoveryService recoveryService;

    private final PaymentCheckoutGateway checkoutGateway;

    private final PaymentRepository paymentRepository;

    private final StripeCheckoutRedirectPolicy redirectPolicy;

    private final StripeGatewayProperties gatewayProperties;

    private final PaymentDeadlineService deadlineService = new PaymentDeadlineService();

    public CustomerCheckoutService(
            BookingPaymentCreationService creationService,
            PaymentBookingAuthorizationService authorizationService,
            TrustedBookingPaymentQuoteProvider quoteProvider,
            StripeCheckoutInitiationService initiationService,
            StripeCheckoutRecoveryService recoveryService,
            PaymentCheckoutGateway checkoutGateway,
            PaymentRepository paymentRepository,
            StripeCheckoutRedirectPolicy redirectPolicy,
            StripeGatewayProperties gatewayProperties) {

        this.creationService = Objects.requireNonNull(creationService);
        this.authorizationService = Objects.requireNonNull(authorizationService);
        this.quoteProvider = Objects.requireNonNull(quoteProvider);
        this.initiationService = Objects.requireNonNull(initiationService);
        this.recoveryService = Objects.requireNonNull(recoveryService);
        this.checkoutGateway = Objects.requireNonNull(checkoutGateway);
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.redirectPolicy = Objects.requireNonNull(redirectPolicy);
        this.gatewayProperties = Objects.requireNonNull(gatewayProperties);
    }

    /**
     * Creates or resumes Checkout for an authenticated booking owner.
     *
     * @param bookingId  trusted booking identifier
     * @param customerId authenticated customer identifier
     * @return Checkout result containing the internal payment reference
     *         and an available hosted Checkout URL
     */
    public CheckoutResult checkout(
            String bookingId,
            String customerId) {

        requireOutsideTransaction();

        String normalizedBookingId = requireIdentifier(bookingId, "Booking ID");

        String normalizedCustomerId = requireIdentifier(customerId, "Customer ID");

        authorizationService.requirePaymentInitiationStatus(
                normalizedBookingId,
                normalizedCustomerId);

        if (!gatewayProperties.isEnabled()) {
            throw new IllegalStateException(
                    "Stripe Checkout is disabled.");
        }

        // Redirect destinations are controlled exclusively by the server.
        var redirects = redirectPolicy.getConfiguredRedirects();

        Payment payment = creationService.createOrGetPayment(
                normalizedBookingId,
                normalizedCustomerId);

        validatePaymentEligibility(
                payment,
                normalizedBookingId,
                normalizedCustomerId);

        // Reuse an attached session instead of submitting another request.
        if (payment.getGatewayRef() != null) {

            return retrieveExistingSession(
                    payment,
                    normalizedBookingId,
                    normalizedCustomerId);
        }

        // An existing reservation may represent an interrupted or
        // ambiguous external submission. Never clear it directly.
        if (hasCheckoutReservation(payment)) {

            boolean recovered = recoveryService.recoverCheckout(payment.getId());

            if (!recovered) {

                // A later request may retry after safe recovery.
                // This request must not create a replacement session.
                throw new IllegalStateException(
                        "Checkout recovery is pending. Retry later.");
            }

            Payment recoveredPayment = reloadPayment(payment.getId());

            return retrieveExistingSession(
                    recoveredPayment,
                    normalizedBookingId,
                    normalizedCustomerId);
        }

        // The initiation service retains responsibility for atomic
        // reservation, gateway submission and session persistence.
        var session = initiationService.initiate(
                payment.getId(),
                normalizedCustomerId,
                redirects.successUrl(),
                redirects.cancelUrl());

        if (session == null
                || session.sessionId() == null
                || session.sessionId().isBlank()
                || session.checkoutUrl() == null
                || session.checkoutUrl().isBlank()) {

            throw new IllegalStateException(
                    "Checkout session response is unavailable.");
        }

        // Revalidate persisted state and booking eligibility before
        // exposing a newly created hosted Checkout URL.
        Payment persistedPayment = reloadPayment(payment.getId());

        validatePaymentEligibility(
                persistedPayment,
                normalizedBookingId,
                normalizedCustomerId);

        if (!session.sessionId().equals(
                persistedPayment.getGatewayRef())
                || hasCheckoutReservation(persistedPayment)) {

            throw new IllegalStateException(
                    "Checkout session persistence requires reconciliation.");
        }

        return new CheckoutResult(
                persistedPayment.getId(),
                CheckoutState.CHECKOUT_READY,
                session.checkoutUrl());
    }

    /**
     * Retrieves and verifies an existing gateway session without
     * submitting a new payment request.
     */
    private CheckoutResult retrieveExistingSession(
            Payment payment,
            String bookingId,
            String customerId) {

        validatePaymentEligibility(
                payment,
                bookingId,
                customerId);

        if (payment.getGatewayRef() == null
                || payment.getGatewayRef().isBlank()
                || hasCheckoutReservation(payment)) {

            throw new IllegalStateException(
                    "Existing Checkout session requires reconciliation.");
        }

        var session = checkoutGateway.retrieveCheckoutSession(
                payment.getGatewayRef(),
                payment.getId(),
                payment.getAmount(),
                payment.getCurrency());

        if (session == null
                || !payment.getGatewayRef().equals(
                        session.sessionId())) {

            throw new IllegalStateException(
                    "Existing Checkout session identity mismatch.");
        }

        if ("complete".equals(session.status())
                && ("paid".equals(session.paymentStatus())
                        || "unpaid".equals(session.paymentStatus()))) {

            // Gateway retrieval is not authoritative payment capture.
            return new CheckoutResult(
                    payment.getId(),
                    CheckoutState.PAYMENT_VERIFICATION_PENDING,
                    null);
        }

        if (!"open".equals(session.status())
                || !"unpaid".equals(session.paymentStatus())
                || session.checkoutUrl() == null
                || session.checkoutUrl().isBlank()) {

            throw new IllegalStateException(
                    "Existing Checkout session is not reusable.");
        }

        // Recheck local state after the external read-only request.
        Payment latestPayment = reloadPayment(payment.getId());

        validatePaymentEligibility(
                latestPayment,
                bookingId,
                customerId);

        if (!session.sessionId().equals(
                latestPayment.getGatewayRef())
                || hasCheckoutReservation(latestPayment)) {

            throw new IllegalStateException(
                    "Checkout session state changed during retrieval.");
        }

        return new CheckoutResult(
                latestPayment.getId(),
                CheckoutState.CHECKOUT_READY,
                session.checkoutUrl());
    }

    /**
     * Revalidates ownership, booking eligibility, financial consistency
     * and the authoritative payment deadline.
     */
    private void validatePaymentEligibility(
            Payment payment,
            String bookingId,
            String customerId) {

        if (payment == null
                || payment.getId() == null
                || payment.getId().isBlank()
                || !bookingId.equals(payment.getBookingId())) {

            throw new IllegalStateException(
                    "Checkout payment identity is invalid.");
        }

        if (!customerId.equals(payment.getCustomerId())) {

            throw new AccessDeniedException(
                    "Customer is not authorized to access this payment.");
        }

        if (payment.getStatus() != PaymentStatus.INITIATED
                || payment.getGatewayPaymentIntentId() != null) {

            throw new IllegalStateException(
                    "Payment is not eligible for Checkout.");
        }

        authorizationService.requirePaymentInitiationStatus(
                bookingId,
                customerId);

        var quote = quoteProvider.getPaymentQuote(bookingId);

        if (quote == null
                || payment.getAmount() == null
                || quote.amount() == null
                || payment.getAmount().compareTo(quote.amount()) != 0
                || !Objects.equals(
                        payment.getCurrency(), quote.currency())
                || payment.getCommissionRate() == null
                || quote.commissionRate() == null
                || payment.getCommissionRate().compareTo(
                        quote.commissionRate()) != 0) {

            throw new IllegalStateException(
                    "Checkout payment does not match trusted booking data.");
        }

        deadlineService.validateAuthoritativePaymentInitiation(
                quote.providerAcceptedAt(),
                quote.paymentDueAt(),
                Instant.now());
    }

    /**
     * Reloads persisted state instead of relying on stale payment data.
     */
    private Payment reloadPayment(String paymentId) {

        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Checkout payment is unavailable."));
    }

    /**
     * Treats any reservation metadata as a possible in-flight submission.
     */
    private boolean hasCheckoutReservation(Payment payment) {

        return payment.getCheckoutReservationToken() != null
                || payment.getCheckoutReservedAt() != null
                || payment.getCheckoutSubmissionStarted() != null;
    }

    /**
     * External gateway operations must not run inside a transaction.
     */
    private void requireOutsideTransaction() {

        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {

            throw new IllegalStateException(
                    "Customer Checkout must run outside an active transaction.");
        }
    }

    private String requireIdentifier(
            String value,
            String fieldName) {

        if (value == null || value.isBlank()) {

            throw new IllegalArgumentException(
                    fieldName + " must not be blank.");
        }

        return value.trim();
    }

    /**
     * Checkout response states are separate from persisted payment states.
     */
    public enum CheckoutState {

        CHECKOUT_READY,

        PAYMENT_VERIFICATION_PENDING
    }

    /**
     * Minimal customer-facing Checkout orchestration result.
     */
    public record CheckoutResult(
            String paymentId,
            CheckoutState state,
            String checkoutUrl) {
    }
}
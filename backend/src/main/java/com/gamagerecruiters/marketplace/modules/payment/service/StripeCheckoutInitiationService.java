package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.payment.config.StripeCheckoutRedirectPolicy;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.gateway.CheckoutNotSubmittedException;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider.BookingPaymentQuote;

/**
 * Initiates hosted Stripe Checkout for an existing payment attempt.
 *
 * <p>Only trusted booking payment values are accepted. This service
 * does not create payment attempts or accept monetary values from HTTP
 * requests. Checkout must not be exposed until the trusted booking
 * quote provider is integrated.</p>
 */
@Service
public class StripeCheckoutInitiationService {

    private final PaymentBookingAuthorizationService authorizationService;
    private final PaymentPersistenceService persistenceService;
    private final TrustedBookingPaymentQuoteProvider quoteProvider;
    private final PaymentCheckoutGateway checkoutGateway;
    private final StripeCheckoutSessionPersistenceService sessionPersistenceService;
    private final StripeCheckoutReservationService reservationService;
    private final StripeCheckoutRedirectPolicy redirectPolicy;
    private final PaymentDeadlineService deadlineService =
            new PaymentDeadlineService();

    public StripeCheckoutInitiationService(
            PaymentBookingAuthorizationService authorizationService,
            PaymentPersistenceService persistenceService,
            TrustedBookingPaymentQuoteProvider quoteProvider,
            PaymentCheckoutGateway checkoutGateway,
            StripeCheckoutSessionPersistenceService sessionPersistenceService,
            StripeCheckoutReservationService reservationService,
            StripeCheckoutRedirectPolicy redirectPolicy) {

        this.authorizationService = Objects.requireNonNull(authorizationService);
        this.persistenceService = Objects.requireNonNull(persistenceService);
        this.quoteProvider = Objects.requireNonNull(quoteProvider);
        this.checkoutGateway = Objects.requireNonNull(checkoutGateway);
        this.sessionPersistenceService =
                Objects.requireNonNull(sessionPersistenceService);
        this.reservationService =
                Objects.requireNonNull(reservationService);
        this.redirectPolicy =
                Objects.requireNonNull(redirectPolicy);
    }

    /**
     * Creates a hosted checkout session for a persisted payment attempt.
     *
     * @param paymentId existing payment identifier
     * @param customerId authenticated customer identifier
     * @param successUrl server-configured success redirect
     * @param cancelUrl server-configured cancellation redirect
     * @return Stripe Checkout Session
     */
    public PaymentCheckoutGateway.CheckoutSession initiate(
            String paymentId,
            String customerId,
            String successUrl,
            String cancelUrl) {

        // Reservation and submission authorization must be committed
        // before any external payment request is submitted.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Checkout initiation must run outside a database transaction");
        }

        requireText(paymentId, "Payment ID");
        requireText(customerId, "Customer ID");
        requireText(successUrl, "Success URL");
        requireText(cancelUrl, "Cancel URL");

        // Reject untrusted redirects before reserving a payment
        // or submitting any external Checkout request.
        redirectPolicy.validate(successUrl, cancelUrl);

        Payment payment = persistenceService.findPaymentById(paymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payment attempt was not found"));

        if (!customerId.trim().equals(payment.getCustomerId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Payment does not belong to the authenticated customer");
        }

        authorizationService.requirePaymentInitiationStatus(
                payment.getBookingId(), customerId.trim());

        if (payment.getStatus() != PaymentStatus.INITIATED) {
            throw new IllegalStateException(
                    "Payment attempt is not eligible for Checkout initiation");
        }

        if (payment.getGatewayRef() != null
                && !payment.getGatewayRef().isBlank()) {
            throw new IllegalStateException(
                    "Payment attempt already has a Checkout Session");
        }

        BookingPaymentQuote quote =
                Objects.requireNonNull(
                        quoteProvider.getPaymentQuote(payment.getBookingId()),
                        "Trusted booking payment quote is unavailable");

        if (quote.providerAcceptedAt() == null) {
            throw new IllegalStateException(
                    "Trusted provider acceptance timestamp is unavailable");
        }

        deadlineService.validateAuthoritativePaymentInitiation(
                quote.providerAcceptedAt(),
                quote.paymentDueAt(),
                Instant.now());

        if (quote.amount() == null
                || quote.amount().signum() <= 0
                || quote.currency() == null
                || quote.currency().isBlank()
                || quote.commissionRate() == null) {
            throw new IllegalStateException(
                    "Trusted booking payment quote is incomplete");
        }

        if (!sameAmount(payment.getAmount(), quote.amount())
                || !quote.currency().equalsIgnoreCase(payment.getCurrency())
                || !sameAmount(payment.getCommissionRate(),
                        quote.commissionRate())) {
            throw new IllegalStateException(
                    "Stored payment does not match the trusted booking quote");
        }

        // Claim the payment before contacting Stripe.
        String reservationToken =
                reservationService.tryReserve(payment.getId());

        if (reservationToken == null) {
            throw new IllegalStateException(
                    "Checkout initiation is already in progress");
        }

        /*
         * Release only a newly acquired reservation when the gateway
         * confirms that this invocation did not submit a request.
         * Ambiguous failures must retain the reservation for recovery.
         */
        // Submission must be authorized atomically before contacting Stripe.
        if (!reservationService.markSubmissionStarted(
                payment.getId(), reservationToken)) {
            throw new IllegalStateException(
                    "Checkout reservation is no longer eligible for submission");
        }

        final PaymentCheckoutGateway.CheckoutSession session;

        try {
            session = checkoutGateway.createCheckoutSession(
                    payment.getId(),
                    payment.getAmount(),
                    payment.getCurrency(),
                    successUrl,
                    cancelUrl);

        } catch (CheckoutNotSubmittedException exception) {
            try {
                boolean released = reservationService.releaseReservation(
                        payment.getId(), reservationToken);

                if (!released) {
                    exception.addSuppressed(new IllegalStateException(
                            "Checkout reservation could not be released safely"));
                }
            } catch (RuntimeException releaseFailure) {
                exception.addSuppressed(releaseFailure);
            }

            throw exception;
        }

        if (session == null
                || session.sessionId() == null
                || session.sessionId().isBlank()
                || session.checkoutUrl() == null
                || session.checkoutUrl().isBlank()) {
            throw new IllegalStateException(
                    "Checkout gateway returned an invalid session");
        }

        // Only the reservation owner can attach the Checkout Session.
        sessionPersistenceService.attachSession(
                payment.getId(),
                session.sessionId(),
                reservationToken);

        return session;
    }

    private static boolean sameAmount(
            BigDecimal first, BigDecimal second) {
        return first != null
                && second != null
                && first.compareTo(second) == 0;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
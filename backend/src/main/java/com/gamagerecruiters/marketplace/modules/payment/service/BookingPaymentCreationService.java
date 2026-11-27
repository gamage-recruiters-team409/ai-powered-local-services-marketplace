package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentCheckoutGateway;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider;
import com.gamagerecruiters.marketplace.modules.payment.gateway.TrustedBookingPaymentQuoteProvider.BookingPaymentQuote;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;

/**
 * Creates or retrieves an initial payment using authoritative booking data.
 *
 * <p>Concurrent requests through this workflow use the same deterministic
 * payment identifier. MongoDB insert semantics prevent overwriting an
 * existing financial record.</p>
 *
 * <p>This service does not contact the payment gateway or authorize
 * replacement attempts after failed or refunded payments.</p>
 */
@Service
public class BookingPaymentCreationService {

    private final PaymentBookingAuthorizationService authorizationService;
    private final TrustedBookingPaymentQuoteProvider quoteProvider;
    private final PaymentCreationService creationService;
    private final PaymentRepository paymentRepository;
    private final PaymentCheckoutGateway checkoutGateway;

    private final PaymentDeadlineService deadlineService =
            new PaymentDeadlineService();

    public BookingPaymentCreationService(
            PaymentBookingAuthorizationService authorizationService,
            TrustedBookingPaymentQuoteProvider quoteProvider,
            PaymentCreationService creationService,
            PaymentRepository paymentRepository,
            PaymentCheckoutGateway checkoutGateway) {

        this.authorizationService =
                Objects.requireNonNull(authorizationService);
        this.quoteProvider = Objects.requireNonNull(quoteProvider);
        this.creationService = Objects.requireNonNull(creationService);
        this.paymentRepository = Objects.requireNonNull(paymentRepository);
        this.checkoutGateway = Objects.requireNonNull(checkoutGateway);
    }

    /**
     * Creates or retrieves the initial payment for an eligible booking.
     *
     * @param bookingId requested booking identifier
     * @param customerId authenticated customer identifier
     * @return persisted initial payment
     */
    public Payment createOrGetPayment(
            String bookingId,
            String customerId) {

        // Duplicate-key recovery must occur outside an aborted transaction.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Payment creation must run outside an active transaction");
        }

        String normalizedBookingId =
                requireIdentifier(bookingId, "Booking ID");

        String normalizedCustomerId =
                requireIdentifier(customerId, "Customer ID");

        authorizationService.requirePaymentInitiationStatus(
                normalizedBookingId, normalizedCustomerId);

        BookingPaymentQuote quote =
                quoteProvider.getPaymentQuote(normalizedBookingId);

        if (quote == null) {
            throw new IllegalStateException(
                    "Trusted booking payment quote is unavailable");
        }

        // Reuse existing validation and commission calculation.
        Payment candidate = creationService.createPayment(
                normalizedBookingId,
                normalizedCustomerId,
                quote.amount(),
                quote.currency(),
                quote.commissionRate());

        // Never create another payment when an existing attempt is present.
        List<Payment> existingPayments =
                paymentRepository.findByBookingId(normalizedBookingId);

        if (!existingPayments.isEmpty()) {
            if (existingPayments.size() != 1) {
                throw new IllegalStateException(
                        "Multiple payment attempts require reconciliation");
            }

            Payment existing = validateReusablePayment(
                    existingPayments.get(0), candidate);

            // Only an already attached session may be inspected after
            // the initiation deadline. This does not authorize submission.
            validateExistingPaymentDeadline(existing, quote);

            return existing;
        }

        // A valid ISO currency is not necessarily supported by the
        // configured Checkout gateway. Reject unsupported currencies
        // before inserting a new financial record.
        //
        // This local capability check never submits a gateway request
        // and does not modify existing payment attempts.
        if (!checkoutGateway.supportsCurrency(candidate.getCurrency())) {
            throw new IllegalArgumentException(
                    "Payment currency is not supported by the checkout gateway");
        }

        candidate.setId(initialPaymentId(normalizedBookingId));

        // Recheck the deadline immediately before attempting persistence.
        deadlineService.validateAuthoritativePaymentInitiation(
                quote.providerAcceptedAt(),
                quote.paymentDueAt(),
                Instant.now());

        try {
            return paymentRepository.insert(candidate);

        } catch (DuplicateKeyException exception) {

            // A concurrent request may have inserted the initial payment.
            Payment existing = paymentRepository
                    .findById(candidate.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Payment creation conflict requires reconciliation",
                            exception));

            Payment reusable = validateReusablePayment(existing, candidate);

            // Apply the same deadline boundary during duplicate-key recovery.
            validateExistingPaymentDeadline(reusable, quote);

            return reusable;
        }
    }

    /**
     * Validates an existing payment before returning it to the caller.
     * Existing gateway and reservation fields are never overwritten.
     */
    private Payment validateReusablePayment(
            Payment existing,
            Payment candidate) {

        if (!candidate.getBookingId().equals(existing.getBookingId())) {
            throw new IllegalStateException(
                    "Existing payment booking identity mismatch");
        }

        if (!candidate.getCustomerId().equals(existing.getCustomerId())) {
            throw new AccessDeniedException(
                    "Customer is not authorized to access this payment");
        }

        if (existing.getStatus() != PaymentStatus.INITIATED) {
            throw new IllegalStateException(
                    "Existing payment status requires reconciliation");
        }

        if (!sameAmount(existing.getAmount(), candidate.getAmount())
                || !Objects.equals(
                        existing.getCurrency(), candidate.getCurrency())
                || !sameAmount(
                        existing.getCommissionRate(),
                        candidate.getCommissionRate())
                || !sameAmount(
                        existing.getCommissionAmount(),
                        candidate.getCommissionAmount())) {

            throw new IllegalStateException(
                    "Existing payment does not match trusted booking data");
        }

        return existing;
    }

    /**
     * Separates existing attached-session retrieval from payment initiation.
     *
     * <p>An elapsed initiation deadline must not prevent inspection of
     * an existing attached session while the booking remains eligible.
     * Payments without an attached session remain subject to the current
     * initiation deadline.</p>
     *
     * @param payment validated existing payment
     * @param quote authoritative booking payment quote
     */
    private void validateExistingPaymentDeadline(
            Payment payment,
            BookingPaymentQuote quote) {

        Instant now = Instant.now();

        boolean hasAttachedSession =
                payment.getGatewayRef() != null
                        && !payment.getGatewayRef().isBlank()
                        && payment.getGatewayPaymentIntentId() == null
                        && payment.getCheckoutReservationToken() == null
                        && payment.getCheckoutReservedAt() == null
                        && payment.getCheckoutSubmissionStarted() == null;

        Instant validationTime = now;

        if (hasAttachedSession
                && quote.paymentDueAt() != null
                && now.isAfter(quote.paymentDueAt())) {

            // Validate the authoritative window without extending it.
            // The caller must independently enforce the deadline before
            // returning an actionable Checkout URL.
            validationTime = quote.paymentDueAt();
        }

        deadlineService.validateAuthoritativePaymentInitiation(
                quote.providerAcceptedAt(),
                quote.paymentDueAt(),
                validationTime);
    }

    /**
     * Produces a stable identifier for the initial payment of a booking.
     */
    private String initialPaymentId(String bookingId) {
        return "wp07-initial-" + UUID.nameUUIDFromBytes(
                ("wp07:initial-payment:" + bookingId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private boolean sameAmount(BigDecimal first, BigDecimal second) {
        return first != null
                && second != null
                && first.compareTo(second) == 0;
    }

    private String requireIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank");
        }

        return value.trim();
    }
}

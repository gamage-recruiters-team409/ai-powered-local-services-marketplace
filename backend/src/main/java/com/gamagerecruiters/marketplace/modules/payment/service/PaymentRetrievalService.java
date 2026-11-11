
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;

/**
 * Provides customer-authorized access to payment records.
 *
 * <p>
 * Payment records are retrieved through PaymentPersistenceService,
 * while booking ownership is verified through
 * PaymentBookingAuthorizationService.
 * </p>
 *
 * <p>
 * This service is intended for authenticated customer workflows.
 * The customer ID must come from the application's trusted security
 * context and must never be accepted as an authoritative client input.
 * </p>
 */
@Service
public class PaymentRetrievalService {

    private final PaymentPersistenceService paymentPersistenceService;
    private final PaymentBookingAuthorizationService authorizationService;

    /**
     * Creates the payment retrieval service.
     *
     * @param paymentPersistenceService internal payment persistence operations
     * @param authorizationService      booking ownership verification service
     */
    public PaymentRetrievalService(
            PaymentPersistenceService paymentPersistenceService,
            PaymentBookingAuthorizationService authorizationService) {
        this.paymentPersistenceService = Objects.requireNonNull(
                paymentPersistenceService,
                "PaymentPersistenceService must not be null");

        this.authorizationService = Objects.requireNonNull(
                authorizationService,
                "PaymentBookingAuthorizationService must not be null");
    }

    /**
     * Retrieves a payment only when it belongs to the authenticated customer.
     *
     * <p>
     * A missing payment and a payment owned by another customer both
     * produce a 404 response. This avoids revealing whether another
     * customer's payment identifier exists.
     * </p>
     *
     * @param paymentId  payment identifier
     * @param customerId authenticated customer's user identifier
     * @return payment belonging to the customer
     * @throws ApiException if the payment is missing or inaccessible
     */
    public Payment getPaymentForCustomer(
            String paymentId,
            String customerId) {
        String validatedPaymentId = requireIdentifier(
                paymentId,
                "Payment ID");

        String validatedCustomerId = requireIdentifier(
                customerId,
                "Customer ID");

        Payment payment = paymentPersistenceService
                .findPaymentById(validatedPaymentId)
                .orElseThrow(this::paymentNotFound);

        if (payment.getBookingId() == null
                || payment.getBookingId().isBlank()) {
            throw paymentNotFound();
        }

        try {
            authorizationService.requireCustomerOwnership(
                    payment.getBookingId(),
                    validatedCustomerId);
        } catch (AccessDeniedException ex) {
            // Do not disclose the existence of another customer's payment.
            throw paymentNotFound();
        } catch (ApiException ex) {
            if (ex.getStatus() == HttpStatus.NOT_FOUND) {
                throw paymentNotFound();
            }
            throw ex;
        }

        return payment;
    }

    /**
     * Retrieves payment attempts for a booking owned by the customer.
     *
     * <p>
     * Ownership is checked before querying payment records.
     * An empty list is returned when the authorized booking has
     * no payment attempts.
     * </p>
     *
     * @param bookingId  booking identifier
     * @param customerId authenticated customer's user identifier
     * @return payment attempts for the authorized booking
     */
    public List<Payment> getPaymentsForBooking(
            String bookingId,
            String customerId) {
        String validatedBookingId = requireIdentifier(
                bookingId,
                "Booking ID");

        String validatedCustomerId = requireIdentifier(
                customerId,
                "Customer ID");

        authorizationService.requireCustomerOwnership(
                validatedBookingId,
                validatedCustomerId);

        return paymentPersistenceService.findPaymentsByBookingId(
                validatedBookingId);
    }

    /**
     * Validates and normalizes a required identifier.
     *
     * @param value     identifier to validate
     * @param fieldName field description for error messages
     * @return trimmed identifier
     */
    private String requireIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PAYMENT_REQUEST",
                    fieldName + " must not be null or blank");
        }

        return value.trim();
    }

    /**
     * Creates a generic not-found exception without exposing
     * information about payment ownership.
     *
     * @return payment-not-found API exception
     */
    private ApiException paymentNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "PAYMENT_NOT_FOUND",
                "Payment not found");
    }
}

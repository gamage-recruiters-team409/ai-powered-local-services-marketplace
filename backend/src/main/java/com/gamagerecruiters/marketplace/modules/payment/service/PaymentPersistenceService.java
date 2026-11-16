
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;

/**
 * Provides internal persistence operations for payment records.
 *
 * <p>
 * This service uses PaymentRepository to store and retrieve payment
 * transactions from MongoDB.
 * </p>
 *
 * <p>
 * Payment creation validation, booking authorization, gateway
 * communication, and payment status transitions are handled
 * separately. Callers must ensure that payment data is trusted
 * and validated before saving it.
 * </p>
 */
@Service
public class PaymentPersistenceService {

    private final PaymentRepository paymentRepository;

    /**
     * Creates the persistence service with its repository dependency.
     *
     * @param paymentRepository repository used for MongoDB operations
     */
    public PaymentPersistenceService(PaymentRepository paymentRepository) {
        this.paymentRepository = Objects.requireNonNull(
                paymentRepository,
                "Payment repository must not be null");
    }

    /**
     * Saves a payment record to MongoDB.
     *
     * <p>
     * This method does not validate payment amounts, authorize bookings,
     * or verify gateway transactions. It must only be called by trusted
     * internal payment workflows.
     * </p>
     *
     * @param payment validated payment record to save
     * @return persisted payment record
     * @throws IllegalArgumentException if payment is null
     */
    public Payment savePayment(Payment payment) {
        if (payment == null) {
            throw new IllegalArgumentException(
                    "Payment must not be null");
        }

        return paymentRepository.save(payment);
    }

    /**
     * Retrieves a payment by its database identifier.
     *
     * @param paymentId payment identifier
     * @return matching payment, if present
     * @throws IllegalArgumentException if payment ID is blank
     */
    public Optional<Payment> findPaymentById(String paymentId) {
        validateIdentifier(paymentId, "Payment ID");

        return paymentRepository.findById(paymentId.trim());
    }

    /**
     * Retrieves all payment attempts associated with a booking.
     *
     * <p>
     * Multiple payment attempts may exist for the same booking.
     * </p>
     *
     * @param bookingId booking identifier
     * @return payment records associated with the booking
     * @throws IllegalArgumentException if booking ID is blank
     */
    public List<Payment> findPaymentsByBookingId(String bookingId) {
        validateIdentifier(bookingId, "Booking ID");

        return paymentRepository.findByBookingId(bookingId.trim());
    }

    /**
     * Retrieves a payment using its external gateway reference.
     *
     * @param gatewayRef external gateway transaction reference
     * @return matching payment, if present
     * @throws IllegalArgumentException if gateway reference is blank
     */
    public Optional<Payment> findPaymentByGatewayRef(String gatewayRef) {
        validateIdentifier(gatewayRef, "Gateway reference");

        return paymentRepository.findByGatewayRef(gatewayRef.trim());
    }

    /**
     * Validates required identifiers before repository operations.
     *
     * @param identifier identifier to validate
     * @param fieldName  name used in validation error messages
     */
    private void validateIdentifier(String identifier, String fieldName) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank");
        }
    }
}

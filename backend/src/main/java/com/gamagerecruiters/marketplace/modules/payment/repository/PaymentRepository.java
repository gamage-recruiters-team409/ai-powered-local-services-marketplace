
package com.gamagerecruiters.marketplace.modules.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;

/**
 * MongoDB repository for payment transaction records.
 *
 * <p>
 * Provides database access methods for retrieving payments
 * by booking ID and payment gateway reference.
 * </p>
 *
 * <p>
 * A booking may have multiple payment attempts, so booking-based
 * lookups return a list rather than a single payment.
 * </p>
 */
public interface PaymentRepository extends MongoRepository<Payment, String> {

    /**
     * Retrieves all payment attempts associated with a booking.
     *
     * @param bookingId the related booking identifier
     * @return payment records associated with the booking
     */
    List<Payment> findByBookingId(String bookingId);

    /**
     * Retrieves a payment using its gateway transaction reference.
     *
     * @param gatewayRef the external payment gateway reference
     * @return the matching payment, if present
     */
    Optional<Payment> findByGatewayRef(String gatewayRef);

    /**
     * Checks whether a gateway reference already exists.
     *
     * <p>
     * This lookup supports duplicate detection, but it does
     * not replace database-level uniqueness or webhook idempotency.
     * </p>
     *
     * @param gatewayRef the external payment gateway reference
     * @return true if a matching reference exists
     */
    boolean existsByGatewayRef(String gatewayRef);
}

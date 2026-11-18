
package com.gamagerecruiters.marketplace.modules.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;

/**
 * MongoDB repository for payment transaction records.
 *
 * <p>
 * Provides database access methods for retrieving payments
 * by booking ID, gateway reference, and verified customer ID.
 * </p>
 *
 * <p>
 * A booking may have multiple payment attempts, so booking-based
 * lookups return a list rather than a single payment.
 * </p>
 *
 * <p>
 * Customer-scoped queries support database-level pagination.
 * Authorization must be enforced by the calling service using
 * the authenticated customer's identity.
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

    /**
     * Retrieves paginated payments belonging to a verified customer.
     *
     * <p>
     * The customer ID must be obtained from the authenticated
     * security context by the calling service or controller.
     * It must never be accepted directly from a client request.
     * </p>
     *
     * <p>
     * Sorting is supplied through Pageable. Customer payment history
     * should be ordered by creation time descending, with a stable
     * secondary sort on the payment identifier.
     * </p>
     *
     * @param customerId authenticated customer identifier
     * @param pageable   pagination and sorting configuration
     * @return page of payments belonging to the customer
     */
    Page<Payment> findByCustomerId(
            String customerId,
            Pageable pageable);
}

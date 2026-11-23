
package com.gamagerecruiters.marketplace.modules.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;

/**
 * Provides persistence operations for booking-specific dispute payout holds.
 *
 * <p>
 * The unique dispute ID supports idempotent hold lookup.
 * Financial reservation and release operations are handled separately
 * by the application service layer.
 * </p>
 */
public interface DisputePayoutHoldRepository
        extends MongoRepository<DisputePayoutHold, String> {

    /**
     * Finds an existing hold associated with a dispute.
     *
     * @param disputeId unique dispute identifier
     * @return matching hold, if present
     */
    Optional<DisputePayoutHold> findByDisputeId(String disputeId);

    /**
     * Retrieves payout holds associated with a booking.
     *
     * @param bookingId booking identifier
     * @return matching payout hold records
     */
    List<DisputePayoutHold> findByBookingId(String bookingId);

    /**
     * Checks whether a hold record already exists for a dispute.
     *
     * @param disputeId unique dispute identifier
     * @return true when a hold record exists
     */
    boolean existsByDisputeId(String disputeId);
}


package com.gamagerecruiters.marketplace.modules.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;

/**
 * MongoDB repository for provider earnings ledger entries.
 *
 * <p>
 * Provides lookup operations for financial events associated
 * with payments, bookings and service providers.
 * </p>
 *
 * <p>
 * Financial ledger records are intended to be append-only.
 * Repository inheritance alone does not enforce immutability.
 * Application services must restrict write operations.
 * </p>
 *
 * <p>
 * Event-key lookups support idempotency checks, but database-level
 * uniqueness is required to prevent concurrent duplicate inserts.
 * </p>
 */
public interface EarningsLedgerRepository
        extends MongoRepository<EarningsLedgerEntry, String> {

    /**
     * Finds a financial event using its idempotency key.
     *
     * @param eventKey the unique identifier of the financial event
     * @return the matching ledger entry, if present
     */
    Optional<EarningsLedgerEntry> findByEventKey(String eventKey);

    /**
     * Checks whether a financial event key already exists.
     *
     * <p>
     * This method is a preliminary duplicate check and must not
     * replace a unique database index.
     * </p>
     *
     * @param eventKey the financial event identifier
     * @return true if an entry with the given key exists
     */
    boolean existsByEventKey(String eventKey);

    /**
     * Retrieves a provider's financial event history,
     * ordered from newest to oldest.
     *
     * @param providerId the provider account identifier
     * @return ledger entries belonging to the provider
     */
    List<EarningsLedgerEntry> findByProviderIdOrderByCreatedAtDesc(
            String providerId);

    /**
     * Retrieves financial events associated with a booking.
     *
     * @param bookingId the booking identifier
     * @return ledger entries associated with the booking
     */
    List<EarningsLedgerEntry> findByBookingIdOrderByCreatedAtDesc(
            String bookingId);

    /**
     * Retrieves financial events associated with a payment.
     *
     * @param paymentId the payment identifier
     * @return ledger entries associated with the payment
     */
    List<EarningsLedgerEntry> findByPaymentIdOrderByCreatedAtDesc(
            String paymentId);
}

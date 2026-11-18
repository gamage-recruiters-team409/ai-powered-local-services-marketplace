package com.gamagerecruiters.marketplace.modules.dispute.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;

/**
 * Used only inside the dispute module. Other modules must go through DisputeService.
 *
 * <p>Every list query returns only visible disputes: intent records whose booking change is not confirmed
 * (bookingSyncState = PENDING) are excluded. Records without the field (created before it existed) are visible.</p>
 */
public interface DisputeRepository extends MongoRepository<Dispute, String> {

    /** The record holding the booking's active-dispute key, visible or PENDING (activeBookingKey is unique). */
    Optional<Dispute> findByActiveBookingKey(String bookingId);

    /** Visible disputes where the user is the customer or the provider. */
    @Query("{ '$or': [ { 'customerId': ?0 }, { 'providerId': ?0 } ], 'bookingSyncState': { '$ne': 'PENDING' } }")
    Page<Dispute> findVisibleByParty(String userId, Pageable pageable);

    @Query("{ 'status': ?0, 'bookingSyncState': { '$ne': 'PENDING' } }")
    Page<Dispute> findVisibleByStatus(DisputeStatus status, Pageable pageable);

    @Query("{ 'bookingSyncState': { '$ne': 'PENDING' } }")
    Page<Dispute> findAllVisible(Pageable pageable);
}

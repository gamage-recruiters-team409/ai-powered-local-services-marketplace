package com.gamagerecruiters.marketplace.modules.dispute.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;

/** Used only inside the dispute module. Other modules must go through DisputeService. */
public interface DisputeRepository extends MongoRepository<Dispute, String> {

    /** True if the booking already has an active dispute (activeBookingKey is set only while active). */
    boolean existsByActiveBookingKey(String bookingId);

    /** Disputes where the user is the customer or the provider. */
    @Query("{ '$or': [ { 'customerId': ?0 }, { 'providerId': ?0 } ] }")
    Page<Dispute> findByParty(String userId, Pageable pageable);

    Page<Dispute> findByStatus(DisputeStatus status, Pageable pageable);
}

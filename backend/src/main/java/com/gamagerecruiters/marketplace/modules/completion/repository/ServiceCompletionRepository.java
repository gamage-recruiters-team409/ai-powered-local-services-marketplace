package com.gamagerecruiters.marketplace.modules.completion.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;

@Repository
public interface ServiceCompletionRepository extends MongoRepository<ServiceCompletion, String> {

    Optional<ServiceCompletion> findByBookingId(String bookingId);

    boolean existsByBookingId(String bookingId);

    /**
     * Completions marked as done before the cutoff that are still waiting for the
     * customer (not confirmed and no issue raised). Used by the 48-hour auto-confirm job.
     */
    List<ServiceCompletion> findByMarkedCompletedAtBeforeAndConfirmedAtIsNullAndIssueRaisedAtIsNull(Instant cutoff);
}

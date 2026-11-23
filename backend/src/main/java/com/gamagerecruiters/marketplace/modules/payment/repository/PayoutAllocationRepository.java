package com.gamagerecruiters.marketplace.modules.payment.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutAllocation;

/**
 * Repository for booking/payment-level payout allocations.
 *
 * <p>
 * Allocation writes must be coordinated by the payout transaction
 * service. This repository does not independently reserve funds.
 * </p>
 */
public interface PayoutAllocationRepository
        extends MongoRepository<PayoutAllocation, String> {

    /**
     * Retrieves all allocations belonging to a payout.
     *
     * @param payoutId payout identifier
     * @return allocations for the payout
     */
    List<PayoutAllocation> findByPayoutId(String payoutId);

    /**
     * Retrieves a provider's payout allocation history.
     *
     * @param providerId provider identifier
     * @return provider allocations
     */
    List<PayoutAllocation> findByProviderId(String providerId);
}
package com.gamagerecruiters.marketplace.modules.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;

/**
 * Persistence repository for provider manual payout requests.
 *
 * <p>
 * The unique request key supports idempotent payout creation.
 * Financial balance updates must be coordinated by the payout
 * service and must not be performed directly through this repository.
 * </p>
 */
public interface PayoutRepository extends MongoRepository<Payout, String> {

    /**
     * Finds an existing payout by its unique request key.
     *
     * @param requestKey payout request idempotency key
     * @return matching payout, if present
     */
    Optional<Payout> findByRequestKey(String requestKey);

    /**
     * Retrieves a provider's payout requests, newest first.
     *
     * @param providerId authenticated provider identifier
     * @return payout history
     */
    List<Payout> findByProviderIdOrderByCreatedAtDesc(String providerId);

    /**
     * Checks whether a payout request key already exists.
     *
     * @param requestKey payout request idempotency key
     * @return true if the request already exists
     */
    boolean existsByRequestKey(String requestKey);

    /**
     * Retrieves a paginated list of a provider's payout requests, newest first.
     *
     * @param providerId authenticated provider identifier
     * @param pageable   pagination information
     * @return paginated payout history
     */
    Page<Payout> findByProviderId(String providerId, Pageable pageable);
}

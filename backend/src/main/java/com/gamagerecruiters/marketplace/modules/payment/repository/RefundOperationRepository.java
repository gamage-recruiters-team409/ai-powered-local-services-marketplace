
package com.gamagerecruiters.marketplace.modules.payment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation.RefundStatus;

/**
 * Repository for persisted customer refund operations.
 *
 * <p>
 * Supports refund lookup, idempotency verification,
 * payment-level refund tracking and reconciliation.
 * </p>
 *
 * <p>
 * Financial mutations and concurrent refund protection
 * must be enforced by the service and persistence layers.
 * </p>
 */
@Repository
public interface RefundOperationRepository
        extends MongoRepository<RefundOperation, String> {

    /**
     * Finds an existing refund operation using its stable
     * business idempotency key.
     */
    Optional<RefundOperation> findByIdempotencyKey(
            String idempotencyKey);

    /**
     * Checks whether a refund operation already exists
     * for the supplied idempotency key.
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Retrieves refund operations associated with a payment.
     */
    List<RefundOperation> findByPaymentId(String paymentId);

    /**
     * Retrieves a bounded page of refund operations for a payment.
     *
     * <p>The caller must verify customer ownership before lookup
     * and supply deterministic sorting and a bounded page size.</p>
     */
    Page<RefundOperation> findByPaymentId(
            String paymentId,
            Pageable pageable);

    /**
     * Finds a refund operation using its gateway-generated
     * refund reference.
     */
    Optional<RefundOperation> findByGatewayRefundReference(
            String gatewayRefundReference);

    /**
     * Retrieves refund operations associated with a dispute.
     */
    List<RefundOperation> findByDisputeId(String disputeId);

    /**
     * Retrieves a bounded batch of refund operations requiring
     * processing or reconciliation, ordered by update time.
     *
     * <p>
     * The caller must supply a bounded page size.
     * </p>
     */
    List<RefundOperation> findByStatusOrderByUpdatedAtAscIdAsc(
            RefundStatus status,
            Pageable pageable);

    /**
     * Selects unresolved refunds and stale processing operations
     * in a single bounded, deterministically ordered batch.
     */
    @Query(value = "{ '$or': [ { 'status': 'REQUIRES_RECONCILIATION' }, "
            + "{ 'status': 'PROCESSING', 'updatedAt': { '$lte': ?0 } } ] }",
            sort = "{ 'updatedAt': 1, '_id': 1 }")
    List<RefundOperation> findReconciliationCandidates(
            Instant staleProcessingCutoff,
            Pageable pageable);
}

package com.gamagerecruiters.marketplace.modules.payment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation.RefundStatus;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentRefundGateway;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentRefundGateway.RefundResult;
import com.gamagerecruiters.marketplace.modules.payment.repository.RefundOperationRepository;

/**
 * Recovers uncertain refund outcomes using read-only gateway queries.
 *
 * <p>Gateway requests are performed outside MongoDB transactions.
 * Verified results are delegated to the existing transactional
 * refund finalization service.</p>
 *
 * <p>Missing or uncertain gateway results never authorize the
 * release of reserved funds or the creation of another refund.</p>
 */
@Service
public class RefundReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(
            RefundReconciliationService.class);

    private static final int MAX_BATCH_SIZE = 100;

    // Conservative recovery threshold for interrupted execution.
    private static final Duration PROCESSING_TIMEOUT =
            Duration.ofMinutes(15);

    private final RefundOperationRepository refundRepository;
    private final PaymentRefundGateway refundGateway;
    private final RefundFinalizationService finalizationService;
    private final MongoTemplate mongoTemplate;

    public RefundReconciliationService(
            RefundOperationRepository refundRepository,
            PaymentRefundGateway refundGateway,
            RefundFinalizationService finalizationService,
            MongoTemplate mongoTemplate) {

        this.refundRepository =
                Objects.requireNonNull(refundRepository);

        this.refundGateway =
                Objects.requireNonNull(refundGateway);

        this.finalizationService =
                Objects.requireNonNull(finalizationService);

        this.mongoTemplate =
                Objects.requireNonNull(mongoTemplate);
    }

    /**
     * Reconciles a persisted refund operation without submitting
     * another gateway refund request.
     *
     * Terminal operations are returned unchanged.
     */
    public RefundOperation reconcileRefund(String operationId) {

        requireNoActiveTransaction();

        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException(
                    "Refund operation ID is required.");
        }

        RefundOperation operation = refundRepository
                .findById(operationId.trim())
                .orElseThrow(() -> new IllegalStateException(
                        "Refund operation was not found."));

        RefundStatus status = operation.getStatus();

        // Terminal operations must never be finalized again.
        if (status == RefundStatus.SUCCEEDED
                || status == RefundStatus.FAILED) {

            return operation;
        }

        // PENDING operations belong to the execution workflow.
        if (status != RefundStatus.PROCESSING
                && status != RefundStatus.REQUIRES_RECONCILIATION) {

            throw new IllegalStateException(
                    "Refund operation is not eligible for reconciliation.");
        }

        // Recheck persisted processing age before contacting the gateway.
        if (status == RefundStatus.PROCESSING
                && (operation.getUpdatedAt() == null
                    || operation.getUpdatedAt().isAfter(
                            Instant.now().minus(PROCESSING_TIMEOUT)))) {

            throw new IllegalStateException(
                    "Refund processing operation is not stale.");
        }

        if (operation.getReservation() == null) {
            throw new IllegalStateException(
                    "Refund reservation is missing.");
        }

        Optional<RefundResult> gatewayResult;

        try {

            // Read-only gateway lookup outside the database transaction.
            gatewayResult = refundGateway.findRefund(
                    operation.getId(),
                    operation.getGatewayPaymentReference(),
                    operation.getGatewayRefundReference(),
                    operation.getAmount(),
                    operation.getCurrency());

        } catch (RuntimeException exception) {

            // Gateway errors do not prove that the refund failed.
            log.warn(
                    "Refund reconciliation lookup unsuccessful: operationId={}",
                    operation.getId());

            return finalizationService.markRequiresReconciliation(
                    operation.getId());
        }

        if (gatewayResult == null || gatewayResult.isEmpty()) {

            // No matching gateway record is not a definitive failure.
            return finalizationService.markRequiresReconciliation(
                    operation.getId());
        }

        // Finalization performs financial validation and atomic updates.
        return finalizationService.finalizeRefund(
                operation.getId(),
                gatewayResult.get());
    }

    /**
     * Reconciles a bounded batch of unresolved refund operations
     * and stale PROCESSING operations.
     *
     * Recovery uses persisted timestamps and read-only gateway
     * queries. A stale operation must never be submitted again.
     *
     * A failure in one operation must not prevent other operations
     * in the batch from being examined.
     */
    public ReconciliationBatchResult reconcileBatch(int batchSize) {

        requireNoActiveTransaction();

        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {

            throw new IllegalArgumentException(
                    "Reconciliation batch size must be between 1 and 100.");
        }

        Instant staleProcessingCutoff =
                Instant.now().minus(PROCESSING_TIMEOUT);

        List<RefundOperation> operations = refundRepository
                .findReconciliationCandidates(
                        staleProcessingCutoff,
                        PageRequest.of(0, batchSize));

        int succeeded = 0;
        int failed = 0;
        int unresolved = 0;
        int errors = 0;

        for (RefundOperation operation : operations) {

            try {

                RefundOperation result =
                        reconcileRefund(operation.getId());

                if (result.getStatus() == RefundStatus.SUCCEEDED) {

                    succeeded++;

                } else if (result.getStatus() == RefundStatus.FAILED) {

                    failed++;

                } else {

                    unresolved++;
                }

            } catch (RuntimeException exception) {

                errors++;

                log.warn(
                        "Refund reconciliation requires investigation: operationId={}",
                        operation.getId());
            }
        }

        return new ReconciliationBatchResult(
                operations.size(),
                succeeded,
                failed,
                unresolved,
                errors);
    }

    /**
     * Prevents external gateway requests from executing inside
     * an active MongoDB or Spring-managed transaction.
     */
    private void requireNoActiveTransaction() {

        if (TransactionSynchronizationManager
                    .isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(
                        mongoTemplate.getMongoDatabaseFactory())) {

            throw new IllegalStateException(
                    "Refund reconciliation must execute outside a transaction.");
        }
    }

    /**
     * Summary of one bounded reconciliation batch.
     *
     * Failed represents a verified terminal refund failure.
     * Errors represents operations requiring further investigation.
     */
    public record ReconciliationBatchResult(
            int examined,
            int succeeded,
            int failed,
            int unresolved,
            int errors) {
    }
}
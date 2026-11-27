package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Explicitly enabled maintenance runner for interrupted refund recovery.
 *
 * Disabled by default. Operators must deliberately enable this runner
 * against the intended database and payment gateway environment.
 *
 * Recovery performs read-only gateway lookups and never submits
 * another refund request.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.payment.refund.reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class RefundReconciliationRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(RefundReconciliationRunner.class);

    private final RefundReconciliationService reconciliationService;
    private final int batchSize;
    private final String operationId;

    public RefundReconciliationRunner(
            RefundReconciliationService reconciliationService,
            @Value("${app.payment.refund.reconciliation.batch-size:25}")
            int batchSize,
            @Value("${app.payment.refund.reconciliation.operation-id:}")
            String operationId) {

        this.reconciliationService =
                Objects.requireNonNull(reconciliationService);

        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException(
                    "Refund reconciliation batch size must be between 1 and 100.");
        }

        this.batchSize = batchSize;
        this.operationId =
                operationId == null ? "" : operationId.trim();
    }

    /**
     * Executes one targeted recovery or bounded recovery batch
     * per explicitly enabled startup.
     */
    @Override
    public void run(ApplicationArguments args) {

        // Explicit operation selection bypasses batch ordering.
        // The reconciliation service retains all existing safety checks.
        if (!operationId.isBlank()) {

            log.info(
                    "Starting targeted refund reconciliation: operationId={}",
                    operationId);

            var operation =
                    reconciliationService.reconcileRefund(operationId);

            log.info(
                    "Targeted refund reconciliation completed: operationId={}, status={}",
                    operationId,
                    operation.getStatus());

            log.warn(
                    "Do not manually release reservations or resubmit refunds. "
                            + "Investigate any unresolved operation.");

            return;
        }

        log.info("Starting controlled refund reconciliation: batchSize={}",
                batchSize);

        var result = reconciliationService.reconcileBatch(batchSize);

        log.info(
                "Refund reconciliation completed: examined={}, "
                        + "succeeded={}, failed={}, unresolved={}, errors={}",
                result.examined(),
                result.succeeded(),
                result.failed(),
                result.unresolved(),
                result.errors());

        if (result.unresolved() > 0 || result.errors() > 0) {

            log.warn(
                    "Some refund operations require further investigation. "
                            + "Do not manually release reservations or resubmit refunds.");
        }
    }
}
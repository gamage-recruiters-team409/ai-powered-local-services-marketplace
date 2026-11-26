package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation.RefundStatus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.gamagerecruiters.marketplace.modules.payment.service
        .RefundReconciliationService.ReconciliationBatchResult;

/**
 * Verifies explicit activation and bounded execution of the
 * controlled refund reconciliation maintenance runner.
 */
class RefundReconciliationRunnerTest {

    private final RefundReconciliationService service =
            mock(RefundReconciliationService.class);

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            RefundReconciliationRunner.class)
                    .withBean(
                            RefundReconciliationService.class,
                            () -> service);

    @Test
    void runnerIsDisabledByDefault() {

        contextRunner.run(context -> {

            assertNull(context.getStartupFailure());

            assertTrue(context.getBeansOfType(
                    RefundReconciliationRunner.class).isEmpty());

            verifyNoInteractions(service);
        });
    }

    @Test
    void explicitlyDisabledRunnerDoesNotExecute() {

        contextRunner
                .withPropertyValues(
                        "app.payment.refund.reconciliation.enabled=false")
                .run(context -> {

                    assertNull(context.getStartupFailure());

                    assertTrue(context.getBeansOfType(
                            RefundReconciliationRunner.class).isEmpty());

                    verifyNoInteractions(service);
                });
    }

    @Test
    void enabledRunnerUsesDefaultBatchSize() {

        when(service.reconcileBatch(25))
                .thenReturn(new ReconciliationBatchResult(
                        0, 0, 0, 0, 0));

        contextRunner
                .withPropertyValues(
                        "app.payment.refund.reconciliation.enabled=true")
                .run(context -> {

                    assertNull(context.getStartupFailure());

                    context.getBean(
                            RefundReconciliationRunner.class).run(null);

                    verify(service, times(1)).reconcileBatch(25);
                    verifyNoMoreInteractions(service);
                });
    }

    @Test
    void enabledRunnerUsesConfiguredBatchSize() {

        when(service.reconcileBatch(50))
                .thenReturn(new ReconciliationBatchResult(
                        3, 1, 1, 1, 0));

        contextRunner
                .withPropertyValues(
                        "app.payment.refund.reconciliation.enabled=true",
                        "app.payment.refund.reconciliation.batch-size=50")
                .run(context -> {

                    assertNull(context.getStartupFailure());

                    context.getBean(
                            RefundReconciliationRunner.class).run(null);

                    verify(service, times(1)).reconcileBatch(50);
                    verifyNoMoreInteractions(service);
                });
    }

    @Test
    void invalidBatchSizeBelowMinimumIsRejected() {

        contextRunner
                .withPropertyValues(
                        "app.payment.refund.reconciliation.enabled=true",
                        "app.payment.refund.reconciliation.batch-size=0")
                .run(context -> {

                    assertNotNull(context.getStartupFailure());
                    verifyNoInteractions(service);
                });
    }

    @Test
    void invalidBatchSizeAboveMaximumIsRejected() {

        contextRunner
                .withPropertyValues(
                        "app.payment.refund.reconciliation.enabled=true",
                        "app.payment.refund.reconciliation.batch-size=101")
                .run(context -> {

                    assertNotNull(context.getStartupFailure());
                    verifyNoInteractions(service);
                });
    }

    @Test
    void unresolvedOperationsDoNotTriggerAutomaticRetry() {

        when(service.reconcileBatch(25))
                .thenReturn(new ReconciliationBatchResult(
                        2, 0, 0, 1, 1));

        RefundReconciliationRunner runner =
                new RefundReconciliationRunner(service, 25, "");

        runner.run(null);

        verify(service, times(1)).reconcileBatch(25);
        verifyNoMoreInteractions(service);
    }

    @Test
    void recoveryFailureDoesNotTriggerAutomaticRetry() {

        when(service.reconcileBatch(25))
                .thenThrow(new IllegalStateException(
                        "Recovery infrastructure unavailable"));

        RefundReconciliationRunner runner =
                new RefundReconciliationRunner(service, 25, "");

        assertThrows(IllegalStateException.class,
                () -> runner.run(null));

        verify(service, times(1)).reconcileBatch(25);
        verifyNoMoreInteractions(service);
    }

    @Test
    void targetedRecoveryUsesConfiguredOperationId() {

        RefundOperation operation = mock(RefundOperation.class);

        when(operation.getStatus())
                .thenReturn(RefundStatus.SUCCEEDED);

        when(service.reconcileRefund("refund-001"))
                .thenReturn(operation);

        contextRunner
                .withPropertyValues(
                        "app.payment.refund.reconciliation.enabled=true",
                        "app.payment.refund.reconciliation.operation-id=refund-001")
                .run(context -> {

                    assertNull(context.getStartupFailure());

                    context.getBean(
                            RefundReconciliationRunner.class).run(null);

                    verify(service).reconcileRefund("refund-001");
                    verifyNoMoreInteractions(service);
                });
    }

    @Test
    void targetedRecoveryTrimsOperationId() {

        RefundOperation operation = mock(RefundOperation.class);

        when(operation.getStatus())
                .thenReturn(RefundStatus.SUCCEEDED);

        when(service.reconcileRefund("refund-001"))
                .thenReturn(operation);

        new RefundReconciliationRunner(
                service, 25, "  refund-001  ").run(null);

        verify(service).reconcileRefund("refund-001");
        verifyNoMoreInteractions(service);
    }

    @Test
    void blankOperationIdUsesBatchRecovery() {

        when(service.reconcileBatch(25))
                .thenReturn(new ReconciliationBatchResult(
                        0, 0, 0, 0, 0));

        new RefundReconciliationRunner(
                service, 25, "   ").run(null);

        verify(service).reconcileBatch(25);
        verifyNoMoreInteractions(service);
    }

    @Test
    void unresolvedTargetedRecoveryDoesNotRetry() {

        RefundOperation operation = mock(RefundOperation.class);

        when(operation.getStatus())
                .thenReturn(RefundStatus.REQUIRES_RECONCILIATION);

        when(service.reconcileRefund("refund-001"))
                .thenReturn(operation);

        new RefundReconciliationRunner(
                service, 25, "refund-001").run(null);

        verify(service).reconcileRefund("refund-001");
        verifyNoMoreInteractions(service);
    }

    @Test
    void targetedRecoveryFailureDoesNotFallbackToBatch() {

        when(service.reconcileRefund("refund-001"))
                .thenThrow(new IllegalStateException(
                        "Recovery infrastructure unavailable"));

        RefundReconciliationRunner runner =
                new RefundReconciliationRunner(
                        service, 25, "refund-001");

        assertThrows(IllegalStateException.class,
                () -> runner.run(null));

        verify(service).reconcileRefund("refund-001");
        verifyNoMoreInteractions(service);
    }

    @Test
    void disabledRunnerIgnoresConfiguredOperationId() {

        contextRunner
                .withPropertyValues(
                        "app.payment.refund.reconciliation.enabled=false",
                        "app.payment.refund.reconciliation.operation-id=refund-001")
                .run(context -> {

                    assertNull(context.getStartupFailure());

                    assertTrue(context.getBeansOfType(
                            RefundReconciliationRunner.class).isEmpty());

                    verifyNoInteractions(service);
                });
    }
}

package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation.RefundStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundReservation;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentRefundGateway;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentRefundGateway.RefundResult;
import com.gamagerecruiters.marketplace.modules.payment.repository.RefundOperationRepository;

/**
 * Verifies safe recovery of interrupted and uncertain refund operations.
 */
class RefundReconciliationServiceTest {

    private RefundOperationRepository repository;
    private PaymentRefundGateway gateway;
    private RefundFinalizationService finalization;
    private RefundReconciliationService service;

    @BeforeEach
    void setUp() {

        repository = mock(RefundOperationRepository.class);
        gateway = mock(PaymentRefundGateway.class);
        finalization = mock(RefundFinalizationService.class);

        MongoTemplate template = mock(MongoTemplate.class);

        when(template.getMongoDatabaseFactory()).thenReturn(
                mock(org.springframework.data.mongodb.MongoDatabaseFactory.class));

        service = new RefundReconciliationService(
                repository, gateway, finalization, template);
    }

    @Test
    void interruptedBeforeGatewaySubmissionRetainsReservation() {

        RefundOperation operation = operation(
                RefundStatus.PROCESSING,
                Instant.now().minusSeconds(1200));

        RefundOperation unresolved = operation(
                RefundStatus.REQUIRES_RECONCILIATION,
                Instant.now());

        when(repository.findById("refund-001"))
                .thenReturn(Optional.of(operation));

        when(gateway.findRefund(
                "refund-001", "pi_test001", null,
                new BigDecimal("200.00"), "LKR"))
                .thenReturn(Optional.empty());

        when(finalization.markRequiresReconciliation("refund-001"))
                .thenReturn(unresolved);

        assertSame(unresolved, service.reconcileRefund("refund-001"));

        verify(finalization, never()).finalizeRefund(anyString(), any());
        verifyNoSubmission();
    }

    @Test
    void interruptedAfterGatewaySubmissionRecoversVerifiedResult() {

        RefundOperation operation = operation(
                RefundStatus.PROCESSING,
                Instant.now().minusSeconds(1200));

        RefundOperation succeeded = operation(
                RefundStatus.SUCCEEDED, Instant.now());

        RefundResult result = mock(RefundResult.class);

        when(repository.findById("refund-001"))
                .thenReturn(Optional.of(operation));

        when(gateway.findRefund(
                "refund-001", "pi_test001", null,
                new BigDecimal("200.00"), "LKR"))
                .thenReturn(Optional.of(result));

        when(finalization.finalizeRefund("refund-001", result))
                .thenReturn(succeeded);

        assertSame(succeeded, service.reconcileRefund("refund-001"));

        verify(finalization).finalizeRefund("refund-001", result);
        verifyNoSubmission();
    }

    @Test
    void freshProcessingOperationIsNotRecovered() {

        RefundOperation operation = operation(
                RefundStatus.PROCESSING, Instant.now());

        when(repository.findById("refund-001"))
                .thenReturn(Optional.of(operation));

        assertThrows(IllegalStateException.class,
                () -> service.reconcileRefund("refund-001"));

        verifyNoInteractions(gateway, finalization);
    }

    @Test
    void processingWithoutTimestampIsRejected() {

        RefundOperation operation = operation(
                RefundStatus.PROCESSING, null);

        when(repository.findById("refund-001"))
                .thenReturn(Optional.of(operation));

        assertThrows(IllegalStateException.class,
                () -> service.reconcileRefund("refund-001"));

        verifyNoInteractions(gateway, finalization);
    }

    @Test
    void gatewayLookupFailureRetainsReservation() {

        RefundOperation operation = operation(
                RefundStatus.PROCESSING,
                Instant.now().minusSeconds(1200));

        RefundOperation unresolved = operation(
                RefundStatus.REQUIRES_RECONCILIATION,
                Instant.now());

        when(repository.findById("refund-001"))
                .thenReturn(Optional.of(operation));

        when(gateway.findRefund(
                "refund-001", "pi_test001", null,
                new BigDecimal("200.00"), "LKR"))
                .thenThrow(new IllegalStateException("Gateway timeout"));

        when(finalization.markRequiresReconciliation("refund-001"))
                .thenReturn(unresolved);

        assertSame(unresolved, service.reconcileRefund("refund-001"));

        verify(finalization, never()).finalizeRefund(anyString(), any());
        verifyNoSubmission();
    }

    @Test
    void terminalRefundRetryDoesNotRepeatGatewayLookup() {

        RefundOperation succeeded = operation(
                RefundStatus.SUCCEEDED, Instant.now());

        when(repository.findById("refund-001"))
                .thenReturn(Optional.of(succeeded));

        assertSame(succeeded, service.reconcileRefund("refund-001"));
        assertSame(succeeded, service.reconcileRefund("refund-001"));

        verifyNoInteractions(gateway, finalization);
    }

    @Test
    void failedTerminalRefundIsReturnedUnchanged() {

        RefundOperation failed = operation(
                RefundStatus.FAILED, Instant.now());

        when(repository.findById("refund-001"))
                .thenReturn(Optional.of(failed));

        assertSame(failed, service.reconcileRefund("refund-001"));

        verifyNoInteractions(gateway, finalization);
    }

    @Test
    void pendingRefundCannotEnterReconciliation() {

        RefundOperation pending = operation(
                RefundStatus.PENDING, Instant.now());

        when(repository.findById("refund-001"))
                .thenReturn(Optional.of(pending));

        assertThrows(IllegalStateException.class,
                () -> service.reconcileRefund("refund-001"));

        verifyNoInteractions(gateway, finalization);
    }

    @Test
    void batchUsesBoundedStaleProcessingCutoff() {

        Instant before = Instant.now().minusSeconds(900);

        when(repository.findReconciliationCandidates(
                any(Instant.class), eq(PageRequest.of(0, 25))))
                .thenReturn(List.of());

        var result = service.reconcileBatch(25);

        Instant after = Instant.now().minusSeconds(900);

        ArgumentCaptor<Instant> cutoff =
                ArgumentCaptor.forClass(Instant.class);

        verify(repository).findReconciliationCandidates(
                cutoff.capture(), eq(PageRequest.of(0, 25)));

        assertFalse(cutoff.getValue().isBefore(before));
        assertFalse(cutoff.getValue().isAfter(after));
        assertEquals(0, result.examined());

        verifyNoInteractions(gateway, finalization);
    }

    @Test
    void invalidBatchSizesAreRejected() {

        assertThrows(IllegalArgumentException.class,
                () -> service.reconcileBatch(0));

        assertThrows(IllegalArgumentException.class,
                () -> service.reconcileBatch(101));

        verifyNoInteractions(repository, gateway, finalization);
    }

    private RefundOperation operation(
            RefundStatus status, Instant updatedAt) {

        RefundOperation operation = mock(RefundOperation.class);

        when(operation.getId()).thenReturn("refund-001");
        when(operation.getStatus()).thenReturn(status);
        when(operation.getUpdatedAt()).thenReturn(updatedAt);

        when(operation.getReservation())
                .thenReturn(mock(RefundReservation.class));

        when(operation.getGatewayPaymentReference())
                .thenReturn("pi_test001");

        when(operation.getAmount())
                .thenReturn(new BigDecimal("200.00"));

        when(operation.getCurrency()).thenReturn("LKR");

        return operation;
    }

    /**
     * Reconciliation must never submit another external refund.
     */
    private void verifyNoSubmission() {

        verify(gateway, never()).createRefund(
                anyString(), anyString(),
                any(BigDecimal.class), anyString());
    }
}
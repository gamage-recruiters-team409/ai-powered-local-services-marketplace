package com.gamagerecruiters.marketplace.modules.payment.integration;

import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementOutcome;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementInstruction;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentRefundGateway;
import com.gamagerecruiters.marketplace.modules.payment.gateway.PaymentRefundGateway.RefundResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.*;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.domain.*;
import com.gamagerecruiters.marketplace.modules.payment.repository.*;
import com.gamagerecruiters.marketplace.modules.payment.service.*;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

/**
 * Real MongoDB integration tests for refund reservation safety.
 *
 * Each test uses an isolated temporary database on the local
 * replica set. No external payment gateway is contacted.
 */
class RefundTransactionMongoIntegrationTest {

    private static final String LOCAL_URI =
            "mongodb://127.0.0.1:27018/?replicaSet=wp07rs";

    private static final String PAYMENT_ID = "payment-001";
    private static final String BOOKING_ID = "booking-001";
    private static final String PROVIDER_ID = "provider-001";
    private static final String CUSTOMER_ID = "customer-001";
    private static final String CURRENCY = "LKR";

    /**
     * A successful reservation must atomically reserve the payment,
     * persist the refund operation and reserve provider earnings.
     */
    @Test
    void reservesRefundAndProviderEarningsAtomically() {
        withDatabase(context -> {

            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            RefundOperation operation = context.refundService()
                    .reserveRefund(
                            PAYMENT_ID,
                            "refund-001",
                            money("200.00"));

            assertNotNull(operation.getId());
            assertEquals(RefundOperation.RefundStatus.PENDING, operation.getStatus());
            assertNotNull(operation.getReservation());

            assertMoney("30.00",
                    operation.getReservation()
                            .commissionReversalAmount());

            assertMoney("170.00",
                    operation.getReservation()
                            .providerEarningsReversalAmount());

            assertMoney("170.00",
                    operation.getReservation().pendingAllocation());

            Payment payment = readPayment(context);

            assertEquals(PaymentStatus.REFUND_PENDING,
                    payment.getStatus());

            assertEquals("refund-001",
                    payment.getRefundReservationToken());

            assertMoney("0.00", payment.getRefundedAmount());

            ProviderEarningsState state = readState(context);

            assertMoney("680.00", state.getPending());
            assertMoney("170.00", state.getRefundReserved());

            assertEquals(1L, context.refundRepository().count());
            assertEquals(2L, context.ledgerRepository().count());
        });
    }

    /**
     * Reusing the same idempotency key must not reserve money twice.
     */
    @Test
    void identicalRetryReusesExistingRefundReservation() {
        withDatabase(context -> {

            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            RefundOperation first = context.refundService()
                    .reserveRefund(
                            PAYMENT_ID,
                            "refund-001",
                            money("200.00"));

            RefundOperation retry = context.refundService()
                    .reserveRefund(
                            PAYMENT_ID,
                            "refund-001",
                            money("200.00"));

            assertEquals(first.getId(), retry.getId());

            assertEquals(1L, context.refundRepository().count());
            assertEquals(2L, context.ledgerRepository().count());

            ProviderEarningsState state = readState(context);

            assertMoney("680.00", state.getPending());
            assertMoney("170.00", state.getRefundReserved());

            // A different amount cannot reuse the same key.
            assertThrows(RuntimeException.class,
                    () -> context.refundService().reserveRefund(
                            PAYMENT_ID,
                            "refund-001",
                            money("300.00")));

            assertEquals(1L, context.refundRepository().count());
            assertEquals(2L, context.ledgerRepository().count());

            assertMoney("170.00",
                    readState(context).getRefundReserved());
        });
    }

    /**
     * Insufficient provider earnings must roll back the payment
     * reservation without creating a refund or ledger event.
     */
    @Test
    void insufficientEarningsRollBackRefundReservation() {
        withDatabase(context -> {

            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            EarningsLedgerEntry debit = newLedgerEvent(
                    "existing-payout-001",
                    EarningsLedgerEventType.REFUND_FROM_PENDING,
                    "750.00");

            context.ledgerService().appendEvent(debit);

            assertMoney("100.00",
                    readState(context).getPending());

            assertThrows(IllegalStateException.class,
                    () -> context.refundService().reserveRefund(
                            PAYMENT_ID,
                            "refund-001",
                            money("200.00")));

            Payment payment = readPayment(context);

            assertEquals(PaymentStatus.CAPTURED,
                    payment.getStatus());

            assertNull(payment.getRefundReservationToken());
            assertNull(payment.getRefundReservedAt());

            assertMoney("0.00", payment.getRefundedAmount());

            assertEquals(0L, context.refundRepository().count());
            assertEquals(2L, context.ledgerRepository().count());

            ProviderEarningsState state = readState(context);

            assertMoney("100.00", state.getPending());
            assertMoney("0.00", state.getRefundReserved());
        });
    }

    /**
     * A second refund must not acquire an already reserved payment.
     */
    @Test
    void rejectsSecondRefundWhileReservationIsActive() {
        withDatabase(context -> {

            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            context.refundService().reserveRefund(
                    PAYMENT_ID,
                    "refund-001",
                    money("200.00"));

            assertThrows(RuntimeException.class,
                    () -> context.refundService().reserveRefund(
                            PAYMENT_ID,
                            "refund-002",
                            money("100.00")));

            assertEquals(1L, context.refundRepository().count());
            assertEquals(2L, context.ledgerRepository().count());

            Payment payment = readPayment(context);

            assertEquals(PaymentStatus.REFUND_PENDING,
                    payment.getStatus());

            assertEquals("refund-001",
                    payment.getRefundReservationToken());

            ProviderEarningsState state = readState(context);

            assertMoney("680.00", state.getPending());
            assertMoney("170.00", state.getRefundReserved());
        });
    }

    /**
     * Creates the original captured payment with a 15% commission.
     */
    /**
     * A verified partial refund settles reserved provider earnings
     * without changing the historical payment or commission amount.
     */
    @Test
    void successfulPartialRefundSettlesReservedEarnings() {
        withDatabase(context -> {
            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            mockGatewayResult(
                    context, "re_partial001", "200.00",
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            RefundOperation operation = context.executionService()
                    .executeRefund(
                            PAYMENT_ID, "refund-001", money("200.00"));

            assertEquals(
                    RefundOperation.RefundStatus.SUCCEEDED,
                    operation.getStatus());

            Payment payment = readPayment(context);

            assertEquals(PaymentStatus.PARTIALLY_REFUNDED,
                    payment.getStatus());

            assertMoney("200.00", payment.getRefundedAmount());
            assertMoney("1000.00", payment.getAmount());
            assertMoney("150.00", payment.getCommissionAmount());

            assertEquals("re_partial001", payment.getRefundReference());
            assertNull(payment.getRefundReservationToken());
            assertNull(payment.getRefundReservedAt());

            assertMoney("680.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getRefundReserved());

            assertEquals(1L, context.refundRepository().count());
            assertEquals(3L, context.ledgerRepository().count());

            verify(context.gateway(), times(1)).createRefund(
                    eq(operation.getId()),
                    eq("pi_test001"),
                    eq(money("200.00")),
                    eq(CURRENCY));
        });
    }

    /**
     * Sequential partial refunds must never exceed the original
     * payment amount or leave residual reserved earnings.
     */
    @Test
    void remainingRefundCompletesPreviouslyPartialRefund() {
        withDatabase(context -> {
            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            mockGatewayResult(
                    context, "re_partial001", "200.00",
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            RefundOperation first = context.executionService()
                    .executeRefund(
                            PAYMENT_ID, "refund-001", money("200.00"));

            assertEquals(
                    RefundOperation.RefundStatus.SUCCEEDED,
                    first.getStatus());

            mockGatewayResult(
                    context, "re_remaining001", "800.00",
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            RefundOperation second = context.executionService()
                    .executeRefund(
                            PAYMENT_ID, "refund-002", money("800.00"));

            assertEquals(
                    RefundOperation.RefundStatus.SUCCEEDED,
                    second.getStatus());

            assertMoney("120.00",
                    second.getReservation().commissionReversalAmount());

            assertMoney("680.00",
                    second.getReservation().providerEarningsReversalAmount());

            Payment payment = readPayment(context);

            assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
            assertMoney("1000.00", payment.getRefundedAmount());
            assertNull(payment.getRefundReservationToken());

            assertMoney("0.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getRefundReserved());

            assertEquals(2L, context.refundRepository().count());
            assertEquals(5L, context.ledgerRepository().count());

            assertThrows(RuntimeException.class,
                    () -> context.executionService().executeRefund(
                            PAYMENT_ID, "refund-003", money("1.00")));

            verify(context.gateway(), times(2)).createRefund(
                    anyString(), anyString(),
                    any(BigDecimal.class), anyString());
        });
    }

    /**
     * A definitive gateway failure restores the original balances.
     */
    @Test
    void failedRefundRestoresOriginalFinancialBalances() {
        withDatabase(context -> {
            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            mockGatewayResult(
                    context, "re_failed001", "200.00",
                    PaymentRefundGateway.RefundStatus.FAILED);

            RefundOperation operation = context.executionService()
                    .executeRefund(
                            PAYMENT_ID, "refund-001", money("200.00"));

            assertEquals(
                    RefundOperation.RefundStatus.FAILED,
                    operation.getStatus());

            Payment payment = readPayment(context);

            assertEquals(PaymentStatus.CAPTURED, payment.getStatus());
            assertMoney("0.00", payment.getRefundedAmount());
            assertNull(payment.getRefundReservationToken());
            assertNull(payment.getRefundReservedAt());

            assertMoney("850.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getRefundReserved());

            assertEquals(1L, context.refundRepository().count());
            assertEquals(3L, context.ledgerRepository().count());
        });
    }

    /**
     * Repeated execution and finalization must not deduct funds twice.
     */
    @Test
    void duplicateFinalizationDoesNotSettleEarningsTwice() {
        withDatabase(context -> {
            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            RefundResult result = mockGatewayResult(
                    context, "re_duplicate001", "200.00",
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            RefundOperation first = context.executionService()
                    .executeRefund(
                            PAYMENT_ID, "refund-001", money("200.00"));

            assertEquals(
                    RefundOperation.RefundStatus.SUCCEEDED,
                    first.getStatus());

            RefundOperation retry = context.executionService()
                    .executeRefund(
                            PAYMENT_ID, "refund-001", money("200.00"));

            RefundOperation finalized = context.finalizationService()
                    .finalizeRefund(first.getId(), result);

            assertEquals(first.getId(), retry.getId());
            assertEquals(first.getId(), finalized.getId());

            assertEquals(
                    RefundOperation.RefundStatus.SUCCEEDED,
                    finalized.getStatus());

            RefundResult conflictingResult = new RefundResult(
                    "re_duplicate001",
                    "pi_test001",
                    money("200.00"),
                    CURRENCY,
                    PaymentRefundGateway.RefundStatus.FAILED);

            assertThrows(IllegalStateException.class,
                    () -> context.finalizationService().finalizeRefund(
                            first.getId(), conflictingResult));

            assertMoney("200.00",
                    readPayment(context).getRefundedAmount());

            assertMoney("680.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getRefundReserved());

            assertEquals(1L, context.refundRepository().count());
            assertEquals(3L, context.ledgerRepository().count());

            verify(context.gateway(), times(1)).createRefund(
                    anyString(), anyString(),
                    any(BigDecimal.class), anyString());
        });
    }

    /**
     * A gateway exception is an uncertain outcome, not proof of failure.
     */
    @Test
    void gatewayTimeoutRetainsReservationAndPreventsReplay() {
        withDatabase(context -> {
            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            when(context.gateway().createRefund(
                    anyString(), anyString(),
                    any(BigDecimal.class), anyString()))
                    .thenThrow(new IllegalStateException(
                            "Simulated gateway timeout"));

            RefundOperation operation = context.executionService()
                    .executeRefund(
                            PAYMENT_ID, "refund-001", money("200.00"));

            assertEquals(
                    RefundOperation.RefundStatus.REQUIRES_RECONCILIATION,
                    operation.getStatus());

            RefundOperation retry = context.executionService()
                    .executeRefund(
                            PAYMENT_ID, "refund-001", money("200.00"));

            assertEquals(operation.getId(), retry.getId());

            assertEquals(
                    RefundOperation.RefundStatus.REQUIRES_RECONCILIATION,
                    retry.getStatus());

            assertEquals(PaymentStatus.REFUND_PENDING,
                    readPayment(context).getStatus());

            assertEquals("refund-001",
                    readPayment(context).getRefundReservationToken());

            assertMoney("0.00",
                    readPayment(context).getRefundedAmount());

            assertMoney("680.00", readState(context).getPending());
            assertMoney("170.00", readState(context).getRefundReserved());

            assertEquals(1L, context.refundRepository().count());
            assertEquals(2L, context.ledgerRepository().count());

            verify(context.gateway(), times(1)).createRefund(
                    anyString(), anyString(),
                    any(BigDecimal.class), anyString());
        });
    }

    /**
     * Pending gateway results retain reservations until a verified
     * terminal outcome is supplied to finalization.
     */
    @Test
    void pendingGatewayRefundCanBeFinalizedAfterReconciliation() {
        withDatabase(context -> {
            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            mockGatewayResult(
                    context, "re_pending001", "200.00",
                    PaymentRefundGateway.RefundStatus.PENDING);

            RefundOperation operation = context.executionService()
                    .executeRefund(
                            PAYMENT_ID, "refund-001", money("200.00"));

            assertEquals(
                    RefundOperation.RefundStatus.REQUIRES_RECONCILIATION,
                    operation.getStatus());

            assertEquals("re_pending001",
                    operation.getGatewayRefundReference());

            assertEquals(PaymentStatus.REFUND_PENDING,
                    readPayment(context).getStatus());

            assertMoney("170.00", readState(context).getRefundReserved());

            RefundResult verifiedResult = new RefundResult(
                    "re_pending001",
                    "pi_test001",
                    money("200.00"),
                    CURRENCY,
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            RefundOperation finalized = context.finalizationService()
                    .finalizeRefund(operation.getId(), verifiedResult);

            assertEquals(
                    RefundOperation.RefundStatus.SUCCEEDED,
                    finalized.getStatus());

            assertEquals(PaymentStatus.PARTIALLY_REFUNDED,
                    readPayment(context).getStatus());

            assertMoney("200.00",
                    readPayment(context).getRefundedAmount());

            assertMoney("680.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getRefundReserved());

            assertEquals(3L, context.ledgerRepository().count());

            verify(context.gateway(), times(1)).createRefund(
                    anyString(), anyString(),
                    any(BigDecimal.class), anyString());
        });
    }

    /**
     * Two competing refund requests must not reserve the same
     * payment and provider earnings twice.
     */
    @Test
    void concurrentRefundRequestsAllowOnlyOneReservation() {
        withDatabase(context -> {
            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            try {
                Future<Boolean> first = executor.submit(
                        () -> attemptReservation(
                                context, "refund-001", ready, start));

                Future<Boolean> second = executor.submit(
                        () -> attemptReservation(
                                context, "refund-002", ready, start));

                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();

                boolean firstSucceeded =
                        first.get(30, TimeUnit.SECONDS);

                boolean secondSucceeded =
                        second.get(30, TimeUnit.SECONDS);

                assertTrue(firstSucceeded ^ secondSucceeded);

                assertEquals(1L, context.refundRepository().count());
                assertEquals(2L, context.ledgerRepository().count());

                assertEquals(PaymentStatus.REFUND_PENDING,
                        readPayment(context).getStatus());

                assertEquals(
                        firstSucceeded ? "refund-001" : "refund-002",
                        readPayment(context).getRefundReservationToken());

                assertMoney("680.00", readState(context).getPending());
                assertMoney("170.00",
                        readState(context).getRefundReserved());

            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);

            } catch (ExecutionException | TimeoutException exception) {
                throw new AssertionError(exception);

            } finally {
                start.countDown();
                executor.shutdownNow();

                try {
                    assertTrue(executor.awaitTermination(
                            30, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        });
    }

    /**
     * Missing committed ledger evidence must prevent gateway execution.
     */
    @Test
    void missingReservationEventPreventsGatewayExecution() {
        withDatabase(context -> {

            RefundOperation operation = reserveTestRefund(context);

            String eventKey =
                    "REFUND_RESERVE:" + operation.getId() + ":PENDING";

            EarningsLedgerEntry entry = context.ledgerRepository()
                    .findByEventKey(eventKey)
                    .orElseThrow();

            // Deliberately simulate corrupted data in the isolated test database.
            context.ledgerRepository().deleteById(entry.getId());

            assertThrows(IllegalStateException.class,
                    () -> context.executionService()
                            .executeReservedRefund(operation.getId()));

            verifyNoInteractions(context.gateway());

            assertEquals(
                    RefundOperation.RefundStatus.PENDING,
                    context.refundRepository()
                            .findById(operation.getId())
                            .orElseThrow().getStatus());

            assertReservationRetained(context);
            assertEquals(1L, context.ledgerRepository().count());
        });
    }

    /**
     * A mismatched reservation amount must prevent financial settlement.
     */
    @Test
    void incorrectReservationAmountPreventsFinalization() {
        withDatabase(context -> {

            RefundOperation operation = createUncertainRefund(context);

            EarningsLedgerEntry entry = context.ledgerRepository()
                    .findByEventKey(
                            "REFUND_RESERVE:" + operation.getId() + ":PENDING")
                    .orElseThrow();

            // Deliberately simulate corrupted ledger evidence.
            entry.setAmount(money("160.00"));
            context.ledgerRepository().save(entry);

            RefundResult result = reconciliationResult(
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            assertThrows(IllegalStateException.class,
                    () -> context.finalizationService()
                            .finalizeRefund(operation.getId(), result));

            assertReservationRetained(context);

            assertEquals(
                    RefundOperation.RefundStatus.REQUIRES_RECONCILIATION,
                    context.refundRepository()
                            .findById(operation.getId())
                            .orElseThrow().getStatus());

            assertEquals(2L, context.ledgerRepository().count());

            assertTrue(context.ledgerRepository()
                    .findByEventKey("REFUND_SETTLE:" + operation.getId())
                    .isEmpty());
        });
    }

    /**
     * A verified successful lookup must settle reserved earnings.
     */
    @Test
    void successfulReconciliationSettlesReservedEarnings() {
        withDatabase(context -> {

            RefundOperation operation = createUncertainRefund(context);

            mockReconciliationResult(
                    context, operation,
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            RefundOperation result = reconciliationService(context)
                    .reconcileRefund(operation.getId());

            assertEquals(
                    RefundOperation.RefundStatus.SUCCEEDED,
                    result.getStatus());

            assertEquals(
                    PaymentStatus.PARTIALLY_REFUNDED,
                    readPayment(context).getStatus());

            assertMoney("200.00", readPayment(context).getRefundedAmount());
            assertNull(readPayment(context).getRefundReservationToken());

            assertMoney("680.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getRefundReserved());

            assertEquals(3L, context.ledgerRepository().count());
            verifySingleGatewayCreation(context);
        });
    }

    /**
     * A verified terminal failure must restore reserved earnings.
     */
    @Test
    void failedReconciliationRestoresReservedEarnings() {
        withDatabase(context -> {

            RefundOperation operation = createUncertainRefund(context);

            mockReconciliationResult(
                    context, operation,
                    PaymentRefundGateway.RefundStatus.FAILED);

            RefundOperation result = reconciliationService(context)
                    .reconcileRefund(operation.getId());

            assertEquals(
                    RefundOperation.RefundStatus.FAILED,
                    result.getStatus());

            assertEquals(
                    PaymentStatus.CAPTURED,
                    readPayment(context).getStatus());

            assertMoney("0.00", readPayment(context).getRefundedAmount());
            assertNull(readPayment(context).getRefundReservationToken());

            assertMoney("850.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getRefundReserved());

            assertEquals(3L, context.ledgerRepository().count());
            verifySingleGatewayCreation(context);
        });
    }

    /**
     * An absent gateway record does not prove refund failure.
     */
    @Test
    void missingGatewayRefundRetainsFinancialReservation() {
        withDatabase(context -> {

            RefundOperation operation = createUncertainRefund(context);

            when(context.gateway().findRefund(
                    operation.getId(),
                    "pi_test001",
                    null,
                    money("200.00"),
                    CURRENCY))
                    .thenReturn(java.util.Optional.empty());

            RefundOperation result = reconciliationService(context)
                    .reconcileRefund(operation.getId());

            assertEquals(
                    RefundOperation.RefundStatus.REQUIRES_RECONCILIATION,
                    result.getStatus());

            assertReservationRetained(context);
            assertEquals(2L, context.ledgerRepository().count());

            verify(context.gateway()).findRefund(
                    operation.getId(),
                    "pi_test001",
                    null,
                    money("200.00"),
                    CURRENCY);

            verifySingleGatewayCreation(context);
        });
    }

    /**
     * Gateway lookup errors must not release reserved funds.
     */
    @Test
    void gatewayLookupFailureRetainsFinancialReservation() {
        withDatabase(context -> {

            RefundOperation operation = createUncertainRefund(context);

            when(context.gateway().findRefund(
                    operation.getId(),
                    "pi_test001",
                    null,
                    money("200.00"),
                    CURRENCY))
                    .thenThrow(new IllegalStateException(
                            "Simulated reconciliation lookup failure"));

            RefundOperation result = reconciliationService(context)
                    .reconcileRefund(operation.getId());

            assertEquals(
                    RefundOperation.RefundStatus.REQUIRES_RECONCILIATION,
                    result.getStatus());

            assertReservationRetained(context);
            assertEquals(2L, context.ledgerRepository().count());

            verify(context.gateway()).findRefund(
                    operation.getId(),
                    "pi_test001",
                    null,
                    money("200.00"),
                    CURRENCY);

            verifySingleGatewayCreation(context);
        });
    }

    /**
     * Repeated reconciliation must not settle earnings twice
     * or repeat the external gateway lookup after finalization.
     */
    @Test
    void repeatedReconciliationDoesNotDuplicateSettlement() {
        withDatabase(context -> {

            RefundOperation operation = createUncertainRefund(context);

            mockReconciliationResult(
                    context, operation,
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            RefundReconciliationService service =
                    reconciliationService(context);

            RefundOperation first =
                    service.reconcileRefund(operation.getId());

            RefundOperation second =
                    service.reconcileRefund(operation.getId());

            assertEquals(first.getId(), second.getId());

            assertEquals(
                    RefundOperation.RefundStatus.SUCCEEDED,
                    second.getStatus());

            assertMoney("200.00", readPayment(context).getRefundedAmount());
            assertMoney("680.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getRefundReserved());

            assertEquals(1L, context.refundRepository().count());
            assertEquals(3L, context.ledgerRepository().count());

            verify(context.gateway(), times(1)).findRefund(
                    operation.getId(),
                    "pi_test001",
                    null,
                    money("200.00"),
                    CURRENCY);

            verifySingleGatewayCreation(context);
        });
    }

    /**
     * Creates a committed refund reservation without a gateway call.
     */
    /**
     * A full dispute refund must consume all held provider earnings.
     */
    @Test
    void fullDisputeRefundSettlesEntireHold() {
        withDatabase(context -> {
            prepareDisputeHold(context);

            RefundOperation reserved = context.refundService()
                    .reserveDisputeRefund(
                            PAYMENT_ID, "dispute-001", money("1000.00"));

            assertMoney("150.00",
                    reserved.getReservation().commissionReversalAmount());
            assertMoney("850.00",
                    reserved.getReservation().heldAllocation());

            mockGatewayResult(
                    context, "re_dispute_full001", "1000.00",
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            RefundOperation result = context.executionService()
                    .executeReservedRefund(reserved.getId());

            assertEquals(RefundOperation.RefundStatus.SUCCEEDED,
                    result.getStatus());

            assertEquals(PaymentStatus.REFUNDED,
                    readPayment(context).getStatus());

            assertMoney("1000.00",
                    readPayment(context).getRefundedAmount());

            assertNull(readPayment(context).getRefundReservationToken());

            assertEquals(DisputePayoutHold.HoldStatus.SETTLED,
                    readDisputeHold(context).getStatus());

            assertMoney("0.00", readState(context).getHeld());
            assertMoney("0.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getAvailable());
            assertMoney("0.00", readState(context).getRefundReserved());

            verifySingleGatewayCreation(context);
        });
    }

    /**
     * A partial refund must reverse only the required earnings and
     * release the remaining held amount to its original source.
     */
    @Test
    void partialDisputeRefundReleasesRemainingEarnings() {
        withDatabase(context -> {
            prepareDisputeHold(context);

            RefundOperation reserved = context.refundService()
                    .reserveDisputeRefund(
                            PAYMENT_ID, "dispute-001", money("200.00"));

            assertMoney("30.00",
                    reserved.getReservation().commissionReversalAmount());
            assertMoney("170.00",
                    reserved.getReservation().heldAllocation());

            mockGatewayResult(
                    context, "re_dispute_partial001", "200.00",
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            RefundOperation result = context.executionService()
                    .executeReservedRefund(reserved.getId());

            assertEquals(RefundOperation.RefundStatus.SUCCEEDED,
                    result.getStatus());

            assertEquals(PaymentStatus.PARTIALLY_REFUNDED,
                    readPayment(context).getStatus());

            assertMoney("200.00",
                    readPayment(context).getRefundedAmount());

            assertEquals(DisputePayoutHold.HoldStatus.SETTLED,
                    readDisputeHold(context).getStatus());

            assertMoney("680.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getAvailable());
            assertMoney("0.00", readState(context).getHeld());
            assertMoney("0.00", readState(context).getRefundReserved());

            assertTrue(context.ledgerRepository().findByEventKey(
                    "DISPUTE_REFUND_REMAINDER_PENDING:"
                            + reserved.getId()).isPresent());

            verifySingleGatewayCreation(context);
        });
    }

    /**
     * A definitive refund failure must restore the original dispute hold.
     */
    @Test
    void failedDisputeRefundRestoresOriginalHold() {
        withDatabase(context -> {
            prepareDisputeHold(context);

            RefundOperation reserved = context.refundService()
                    .reserveDisputeRefund(
                            PAYMENT_ID, "dispute-001", money("200.00"));

            mockGatewayResult(
                    context, "re_dispute_failed001", "200.00",
                    PaymentRefundGateway.RefundStatus.FAILED);

            RefundOperation result = context.executionService()
                    .executeReservedRefund(reserved.getId());

            assertEquals(RefundOperation.RefundStatus.FAILED,
                    result.getStatus());

            assertEquals(PaymentStatus.CAPTURED,
                    readPayment(context).getStatus());

            assertMoney("0.00", readPayment(context).getRefundedAmount());
            assertNull(readPayment(context).getRefundReservationToken());

            assertEquals(DisputePayoutHold.HoldStatus.APPLIED,
                    readDisputeHold(context).getStatus());

            assertMoney("850.00", readState(context).getHeld());
            assertMoney("0.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getRefundReserved());

            verifySingleGatewayCreation(context);
        });
    }

    /**
     * An uncertain gateway result must retain financial reservations
     * until reconciliation confirms the actual refund outcome.
     */
    @Test
    void uncertainDisputeRefundRetainsHoldUntilReconciliation() {
        withDatabase(context -> {
            prepareDisputeHold(context);

            RefundOperation reserved = context.refundService()
                    .reserveDisputeRefund(
                            PAYMENT_ID, "dispute-001", money("200.00"));

            when(context.gateway().createRefund(
                    anyString(), anyString(),
                    any(BigDecimal.class), anyString()))
                    .thenThrow(new IllegalStateException(
                            "Simulated dispute refund timeout"));

            RefundOperation uncertain = context.executionService()
                    .executeReservedRefund(reserved.getId());

            assertEquals(
                    RefundOperation.RefundStatus.REQUIRES_RECONCILIATION,
                    uncertain.getStatus());

            assertEquals(PaymentStatus.REFUND_PENDING,
                    readPayment(context).getStatus());

            assertEquals(DisputePayoutHold.HoldStatus.SETTLING,
                    readDisputeHold(context).getStatus());

            assertMoney("680.00", readState(context).getHeld());
            assertMoney("170.00", readState(context).getRefundReserved());

            // Provider release must not bypass an active refund.
            assertThrows(RuntimeException.class,
                    () -> context.holdService().releaseHold("dispute-001"));

            mockReconciliationResult(
                    context, uncertain,
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            RefundOperation result = reconciliationService(context)
                    .reconcileRefund(uncertain.getId());

            assertEquals(RefundOperation.RefundStatus.SUCCEEDED,
                    result.getStatus());

            assertEquals(DisputePayoutHold.HoldStatus.SETTLED,
                    readDisputeHold(context).getStatus());

            assertMoney("200.00", readPayment(context).getRefundedAmount());
            assertMoney("680.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getHeld());
            assertMoney("0.00", readState(context).getRefundReserved());

            verifySingleGatewayCreation(context);
        });
    }

    /**
     * Repeated settlement must not create duplicate gateway refunds
     * or duplicate ledger movements.
     */
    @Test
    void repeatedDisputeRefundDoesNotDuplicateFinancialSettlement() {
        withDatabase(context -> {
            prepareDisputeHold(context);

            RefundOperation reserved = context.refundService()
                    .reserveDisputeRefund(
                            PAYMENT_ID, "dispute-001", money("200.00"));

            mockGatewayResult(
                    context, "re_dispute_retry001", "200.00",
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            RefundOperation first = context.executionService()
                    .executeReservedRefund(reserved.getId());

            long ledgerCount = context.ledgerRepository().count();

            RefundOperation retry = context.refundService()
                    .reserveDisputeRefund(
                            PAYMENT_ID, "dispute-001", money("200.00"));

            RefundOperation second = context.executionService()
                    .executeReservedRefund(retry.getId());

            assertEquals(first.getId(), second.getId());

            assertEquals(RefundOperation.RefundStatus.SUCCEEDED,
                    second.getStatus());

            assertEquals(1L, context.refundRepository().count());
            assertEquals(ledgerCount, context.ledgerRepository().count());

            assertMoney("200.00", readPayment(context).getRefundedAmount());
            assertMoney("680.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getRefundReserved());

            verifySingleGatewayCreation(context);
        });
    }

    /**
     * An ordinary refund must not bypass the dispute settlement workflow.
     */
    @Test
    void ordinaryRefundCannotConsumeDisputedPayment() {
        withDatabase(context -> {
            prepareDisputeHold(context);

            long ledgerCount = context.ledgerRepository().count();

            assertThrows(RuntimeException.class,
                    () -> context.refundService().reserveRefund(
                            PAYMENT_ID, "ordinary-refund", money("200.00")));

            assertEquals(0L, context.refundRepository().count());
            assertEquals(ledgerCount, context.ledgerRepository().count());

            assertEquals(PaymentStatus.CAPTURED,
                    readPayment(context).getStatus());

            assertNull(readPayment(context).getRefundReservationToken());

            assertEquals(DisputePayoutHold.HoldStatus.APPLIED,
                    readDisputeHold(context).getStatus());

            assertMoney("850.00", readState(context).getHeld());
            assertMoney("0.00", readState(context).getRefundReserved());

            verifyNoInteractions(context.gateway());
        });
    }

    /**
     * Missing committed reservation evidence must block gateway execution.
     */
    @Test
    void corruptedDisputeReservationPreventsGatewayExecution() {
        withDatabase(context -> {
            prepareDisputeHold(context);

            RefundOperation reserved = context.refundService()
                    .reserveDisputeRefund(
                            PAYMENT_ID, "dispute-001", money("200.00"));

            EarningsLedgerEntry entry = context.ledgerRepository()
                    .findByEventKey(
                            "REFUND_RESERVE:" + reserved.getId() + ":HELD")
                    .orElseThrow();

            context.ledgerRepository().deleteById(entry.getId());

            assertThrows(IllegalStateException.class,
                    () -> context.executionService()
                            .executeReservedRefund(reserved.getId()));

            verifyNoInteractions(context.gateway());

            assertEquals(RefundOperation.RefundStatus.PENDING,
                    context.refundRepository()
                            .findById(reserved.getId())
                            .orElseThrow().getStatus());

            assertEquals(DisputePayoutHold.HoldStatus.SETTLING,
                    readDisputeHold(context).getStatus());

            assertEquals(PaymentStatus.REFUND_PENDING,
                    readPayment(context).getStatus());
        });
    }

    /**
     * Prepares a disputed booking with committed provider earnings
     * and an applied financial hold.
     */
    private void prepareDisputeHold(TestContext context) {

        seedPayment(context);
        seedPendingEarnings(context, "850.00");

        when(context.bookingFacade().getBookingParticipants(BOOKING_ID))
                .thenReturn(new BookingParticipants(
                        BOOKING_ID,
                        null,
                        "listing-001",
                        CUSTOMER_ID,
                        PROVIDER_ID,
                        BookingStatus.DISPUTED,
                        null,
                        null));

        DisputePayoutHold hold = new DisputePayoutHold(
                "dispute-001",
                BOOKING_ID,
                PAYMENT_ID,
                PROVIDER_ID,
                money("850.00"),
                CURRENCY);

        context.holdRepository().save(hold);

        context.ledgerService().appendEvent(
                newLedgerEvent(
                        "DISPUTE_HOLD:dispute-001",
                        EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING,
                        "850.00"));

        hold.markApplied();
        context.holdRepository().save(hold);

        assertMoney("850.00", readState(context).getHeld());
        assertMoney("0.00", readState(context).getPending());
    }

    /**
     * Verifies full refund settlement through the WP09 adapter
     * using real payment, ledger and hold transactions.
     */
    @Test
    void adapterFullRefundConfirmsCommittedSettlement() {
        withDatabase(context -> {
            prepareDisputeHold(context);

            mockGatewayResult(
                    context, "re_adapter_full001", "1000.00",
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            Wp07DisputeSettlementAdapter adapter =
                    createSettlementAdapter(context);

            assertEquals(SettlementOutcome.APPLIED,
                    adapter.settle(disputeInstruction(
                            ResolutionAction.REFUND_FULL, 100)));

            assertEquals(PaymentStatus.REFUNDED,
                    readPayment(context).getStatus());

            assertMoney("1000.00",
                    readPayment(context).getRefundedAmount());

            assertEquals(DisputePayoutHold.HoldStatus.SETTLED,
                    readDisputeHold(context).getStatus());

            assertMoney("0.00", readState(context).getHeld());
            assertMoney("0.00", readState(context).getRefundReserved());

            assertEquals(1L, context.refundRepository().count());

            verifySingleGatewayCreation(context);
        });
    }

    /**
     * Repeated adapter settlement must reuse the committed refund
     * without repeating external or internal financial operations.
     */
    @Test
    void adapterPartialRefundRetryIsIdempotent() {
        withDatabase(context -> {
            prepareDisputeHold(context);

            mockGatewayResult(
                    context, "re_adapter_partial001", "200.00",
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            Wp07DisputeSettlementAdapter adapter =
                    createSettlementAdapter(context);

            SettlementInstruction instruction = disputeInstruction(
                    ResolutionAction.REFUND_PARTIAL, 20);

            assertEquals(SettlementOutcome.APPLIED,
                    adapter.settle(instruction));

            long ledgerCount = context.ledgerRepository().count();

            assertEquals(SettlementOutcome.APPLIED,
                    adapter.settle(instruction));

            assertEquals(PaymentStatus.PARTIALLY_REFUNDED,
                    readPayment(context).getStatus());

            assertMoney("200.00",
                    readPayment(context).getRefundedAmount());

            assertEquals(DisputePayoutHold.HoldStatus.SETTLED,
                    readDisputeHold(context).getStatus());

            assertMoney("680.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getHeld());
            assertMoney("0.00", readState(context).getRefundReserved());

            assertEquals(1L, context.refundRepository().count());
            assertEquals(ledgerCount, context.ledgerRepository().count());

            verifySingleGatewayCreation(context);
        });
    }

    /**
     * Verifies an actual mixed-source hold and partial refund.
     * The refund consumes pending-origin earnings first and
     * releases the remaining balances to their original sources.
     */
    @Test
    void mixedSourceDisputeRefundRestoresRemainingBalances() {
        withDatabase(context -> {
            seedPayment(context);
            seedPendingEarnings(context, "850.00");

            context.ledgerService().appendEvent(
                    newLedgerEvent(
                            "earning-available:" + PAYMENT_ID,
                            EarningsLedgerEventType.EARNING_AVAILABLE,
                            "500.00"));

            assertMoney("350.00", readState(context).getPending());
            assertMoney("500.00", readState(context).getAvailable());

            when(context.bookingFacade().getBookingParticipants(BOOKING_ID))
                    .thenReturn(new BookingParticipants(
                            BOOKING_ID,
                            null,
                            "listing-001",
                            CUSTOMER_ID,
                            PROVIDER_ID,
                            BookingStatus.DISPUTED,
                            null,
                            null));

            DisputePayoutHold hold = new DisputePayoutHold(
                    "dispute-001",
                    BOOKING_ID,
                    PAYMENT_ID,
                    PROVIDER_ID,
                    money("850.00"),
                    CURRENCY);

            context.holdRepository().save(hold);

            DisputePayoutHold applied =
                    context.holdService().applyHold("dispute-001");

            assertEquals(DisputePayoutHold.HoldStatus.APPLIED,
                    applied.getStatus());

            assertMoney("850.00", readState(context).getHeld());
            assertMoney("0.00", readState(context).getPending());
            assertMoney("0.00", readState(context).getAvailable());

            mockGatewayResult(
                    context, "re_mixed_partial001", "200.00",
                    PaymentRefundGateway.RefundStatus.SUCCEEDED);

            Wp07DisputeSettlementAdapter adapter =
                    createSettlementAdapter(context);

            assertEquals(SettlementOutcome.APPLIED,
                    adapter.settle(disputeInstruction(
                            ResolutionAction.REFUND_PARTIAL, 20)));

            assertEquals(PaymentStatus.PARTIALLY_REFUNDED,
                    readPayment(context).getStatus());

            assertMoney("200.00",
                    readPayment(context).getRefundedAmount());

            assertEquals(DisputePayoutHold.HoldStatus.SETTLED,
                    readDisputeHold(context).getStatus());

            assertMoney("180.00", readState(context).getPending());
            assertMoney("500.00", readState(context).getAvailable());
            assertMoney("0.00", readState(context).getHeld());
            assertMoney("0.00", readState(context).getRefundReserved());

            RefundOperation operation = context.refundRepository()
                    .findByIdempotencyKey("dispute-dispute-001")
                    .orElseThrow();

            assertTrue(context.ledgerRepository().findByEventKey(
                    "DISPUTE_REFUND_REMAINDER_PENDING:"
                            + operation.getId()).isPresent());

            assertTrue(context.ledgerRepository().findByEventKey(
                    "DISPUTE_REFUND_REMAINDER_AVAILABLE:"
                            + operation.getId()).isPresent());

            verifySingleGatewayCreation(context);
        });
    }

    /**
     * Connects the settlement adapter to real financial services.
     * Only the external gateway and booking facade remain mocked.
     */
    private Wp07DisputeSettlementAdapter createSettlementAdapter(
            TestContext context) {

        BookingFinancialSnapshotService snapshotService =
                new BookingFinancialSnapshotService(
                        new PaymentPersistenceService(
                                context.paymentRepository()),
                        context.bookingFacade());

        return new Wp07DisputeSettlementAdapter(
                snapshotService,
                context.holdRepository(),
                context.holdService(),
                new DisputeSettlementCalculator(),
                context.refundRepository(),
                context.refundService(),
                context.executionService(),
                reconciliationService(context));
    }

    private SettlementInstruction disputeInstruction(
            ResolutionAction action,
            int refundPercent) {

        return new SettlementInstruction(
                "dispute-001",
                BOOKING_ID,
                CUSTOMER_ID,
                PROVIDER_ID,
                action,
                refundPercent,
                0,
                "dispute-dispute-001");
    }

    private DisputePayoutHold readDisputeHold(TestContext context) {
        return context.holdRepository()
                .findByDisputeId("dispute-001")
                .orElseThrow();
    }

    private RefundOperation reserveTestRefund(TestContext context) {

        seedPayment(context);
        seedPendingEarnings(context, "850.00");

        return context.refundService().reserveRefund(
                PAYMENT_ID, "refund-001", money("200.00"));
    }

    /**
     * Simulates an uncertain external refund submission.
     */
    private RefundOperation createUncertainRefund(TestContext context) {

        seedPayment(context);
        seedPendingEarnings(context, "850.00");

        when(context.gateway().createRefund(
                anyString(),
                anyString(),
                any(BigDecimal.class),
                anyString()))
                .thenThrow(new IllegalStateException(
                        "Simulated uncertain gateway submission"));

        RefundOperation operation = context.executionService()
                .executeRefund(
                        PAYMENT_ID, "refund-001", money("200.00"));

        assertEquals(
                RefundOperation.RefundStatus.REQUIRES_RECONCILIATION,
                operation.getStatus());

        assertNull(operation.getGatewayRefundReference());

        return operation;
    }

    private RefundReconciliationService reconciliationService(
            TestContext context) {

        return new RefundReconciliationService(
                context.refundRepository(),
                context.gateway(),
                context.finalizationService(),
                context.template());
    }

    private RefundResult reconciliationResult(
            PaymentRefundGateway.RefundStatus status) {

        return new RefundResult(
                "re_reconciled001",
                "pi_test001",
                money("200.00"),
                CURRENCY,
                status);
    }

    /**
     * Supplies a verified lookup result and checks transaction isolation.
     */
    private void mockReconciliationResult(
            TestContext context,
            RefundOperation operation,
            PaymentRefundGateway.RefundStatus status) {

        when(context.gateway().findRefund(
                operation.getId(),
                "pi_test001",
                null,
                money("200.00"),
                CURRENCY))
                .thenAnswer(invocation -> {

                    assertFalse(TransactionSynchronizationManager
                            .isActualTransactionActive());

                    assertFalse(TransactionSynchronizationManager
                            .hasResource(context.template()
                                    .getMongoDatabaseFactory()));

                    return java.util.Optional.of(
                            reconciliationResult(status));
                });
    }

    private void assertReservationRetained(TestContext context) {

        Payment payment = readPayment(context);

        assertEquals(PaymentStatus.REFUND_PENDING, payment.getStatus());
        assertEquals("refund-001", payment.getRefundReservationToken());

        assertMoney("0.00", payment.getRefundedAmount());
        assertMoney("680.00", readState(context).getPending());
        assertMoney("170.00", readState(context).getRefundReserved());

        assertEquals(1L, context.refundRepository().count());
    }

    private void verifySingleGatewayCreation(TestContext context) {

        verify(context.gateway(), times(1)).createRefund(
                anyString(),
                anyString(),
                any(BigDecimal.class),
                anyString());
    }

    private boolean attemptReservation(
            TestContext context,
            String key,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {

        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Concurrent start timed out");
        }

        try {
            context.refundService().reserveRefund(
                    PAYMENT_ID, key, money("200.00"));

            return true;

        } catch (IllegalStateException |
                 org.springframework.dao.DataAccessException |
                 com.mongodb.MongoException exception) {

            return false;
        }
    }

    /**
     * Supplies a deterministic gateway response without contacting Stripe.
     * Also verifies that external execution occurs outside a transaction.
     */
    private RefundResult mockGatewayResult(
            TestContext context,
            String reference,
            String amount,
            PaymentRefundGateway.RefundStatus status) {

        RefundResult result = new RefundResult(
                reference,
                "pi_test001",
                money(amount),
                CURRENCY,
                status);

        when(context.gateway().createRefund(
                anyString(),
                eq("pi_test001"),
                eq(money(amount)),
                eq(CURRENCY)))
                .thenAnswer(invocation -> {

                    assertFalse(TransactionSynchronizationManager
                            .isActualTransactionActive());

                    assertFalse(TransactionSynchronizationManager
                            .hasResource(context.template()
                                    .getMongoDatabaseFactory()));

                    return result;
                });

        return result;
    }

    private void seedPayment(TestContext context) {

        Payment payment = new Payment();

        payment.setId(PAYMENT_ID);
        payment.setBookingId(BOOKING_ID);
        payment.setCustomerId(CUSTOMER_ID);
        payment.setAmount(money("1000.00"));
        payment.setCommissionAmount(money("150.00"));
        payment.setRefundedAmount(money("0.00"));
        payment.setCurrency(CURRENCY);
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setGatewayPaymentIntentId("pi_test001");
        payment.setPaidAt(Instant.now());
        payment.setCreatedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());

        context.template().insert(payment);
    }

    private void seedPendingEarnings(
            TestContext context,
            String amount) {

        context.ledgerService().appendEvent(
                newLedgerEvent(
                        "earning-pending:" + PAYMENT_ID,
                        EarningsLedgerEventType.EARNING_PENDING,
                        amount));
    }

    private EarningsLedgerEntry newLedgerEvent(
            String key,
            EarningsLedgerEventType type,
            String amount) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey(key);
        entry.setPaymentId(PAYMENT_ID);
        entry.setBookingId(BOOKING_ID);
        entry.setProviderId(PROVIDER_ID);
        entry.setCurrency(CURRENCY);
        entry.setEventType(type);
        entry.setAmount(money(amount));
        entry.setCreatedAt(Instant.now());

        return entry;
    }

    private Payment readPayment(TestContext context) {

        return context.paymentRepository()
                .findById(PAYMENT_ID)
                .orElseThrow();
    }

    private ProviderEarningsState readState(TestContext context) {

        ProviderEarningsState state = context.template().findById(
                new ProviderEarningsState(
                        PROVIDER_ID, CURRENCY).getId(),
                ProviderEarningsState.class);

        assertNotNull(state);

        return state;
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    private static void assertMoney(
            String expected,
            BigDecimal actual) {

        assertNotNull(actual);

        assertEquals(0, money(expected).compareTo(actual));
    }

    /**
     * Runs each test against its own isolated MongoDB database.
     * All financial services share the same transaction manager.
     */
    private void withDatabase(Consumer<TestContext> action) {

        String databaseName = "wp07_refund_test_"
                + UUID.randomUUID().toString().replace("-", "");

        try (MongoClient client = MongoClients.create(LOCAL_URI)) {

            try {
                MongoTemplate template =
                        new MongoTemplate(client, databaseName);

                template.createCollection("payments");
                template.createCollection("earnings_ledger");
                template.createCollection("provider_earnings_state");
                template.createCollection("payment_refund_operations");
                template.createCollection("dispute_payout_holds");

                template.getCollection("dispute_payout_holds")
                        .createIndex(
                                Indexes.ascending("disputeId"),
                                new IndexOptions().unique(true));

                template.getCollection("earnings_ledger")
                        .createIndex(
                                Indexes.ascending("eventKey"),
                                new IndexOptions().unique(true));

                template.getCollection("earnings_ledger")
                        .createIndex(
                                Indexes.ascending(
                                        "providerId",
                                        "currency",
                                        "sequenceNumber"),
                                new IndexOptions().unique(true));

                template.getCollection("payment_refund_operations")
                        .createIndex(
                                Indexes.ascending("idempotencyKey"),
                                new IndexOptions().unique(true));

                MongoRepositoryFactory factory =
                        new MongoRepositoryFactory(template);

                PaymentRepository paymentRepository =
                        factory.getRepository(PaymentRepository.class);

                RefundOperationRepository refundRepository =
                        factory.getRepository(
                                RefundOperationRepository.class);

                EarningsLedgerRepository ledgerRepository =
                        factory.getRepository(
                                EarningsLedgerRepository.class);

                ProviderEarningsStateRepository stateRepository =
                        factory.getRepository(
                                ProviderEarningsStateRepository.class);

                DisputePayoutHoldRepository holdRepository =
                        factory.getRepository(
                                DisputePayoutHoldRepository.class);

                MongoTransactionManager transactionManager =
                        new MongoTransactionManager(
                                template.getMongoDatabaseFactory());

                EarningsLedgerTransactionService ledgerTransaction =
                        new EarningsLedgerTransactionService(
                                ledgerRepository,
                                stateRepository,
                                template,
                                transactionManager);

                EarningsLedgerService ledgerService =
                        new EarningsLedgerService(
                                ledgerRepository,
                                ledgerTransaction);

                BookingFacade bookingFacade =
                        mock(BookingFacade.class);

                when(bookingFacade.getBookingParticipants(BOOKING_ID))
                        .thenReturn(new BookingParticipants(
                                BOOKING_ID,
                                null,
                                "listing-001",
                                CUSTOMER_ID,
                                PROVIDER_ID,
                                BookingStatus.COMPLETED,
                                null,
                                null));

                RefundReservationService reservationService =
                        new RefundReservationService(template);

                RefundEarningsAllocationService allocationService =
                        new RefundEarningsAllocationService(
                                ledgerRepository,
                                new EarningsBalanceCalculator());

                DisputePayoutHoldTransactionService holdService =
                        new DisputePayoutHoldTransactionService(
                                holdRepository,
                                ledgerRepository,
                                new EarningsBalanceCalculator(),
                                ledgerTransaction,
                                transactionManager);

                RefundTransactionService refundService =
                        new RefundTransactionService(
                                paymentRepository,
                                refundRepository,
                                bookingFacade,
                                new RefundCommissionCalculator(),
                                reservationService,
                                allocationService,
                                ledgerTransaction,
                                holdRepository,
                                holdService,
                                template,
                                transactionManager);

                PaymentRefundGateway gateway =
                        mock(PaymentRefundGateway.class);

                RefundFinalizationService finalizationService =
                        new RefundFinalizationService(
                                paymentRepository,
                                refundRepository,
                                ledgerTransaction,
                                template,
                                transactionManager);

                RefundExecutionService executionService =
                        new RefundExecutionService(
                                refundService,
                                finalizationService,
                                gateway,
                                refundRepository,
                                paymentRepository,
                                template);

                action.accept(new TestContext(
                        template,
                        paymentRepository,
                        refundRepository,
                        ledgerRepository,
                        ledgerService,
                        refundService,
                        finalizationService,
                        executionService,
                        gateway,
                        holdRepository,
                        holdService,
                        bookingFacade));

            } finally {
                // Never drop shared or production databases.
                client.getDatabase(databaseName).drop();
            }
        }
    }

    private record TestContext(
            MongoTemplate template,
            PaymentRepository paymentRepository,
            RefundOperationRepository refundRepository,
            EarningsLedgerRepository ledgerRepository,
            EarningsLedgerService ledgerService,
            RefundTransactionService refundService,
            RefundFinalizationService finalizationService,
            RefundExecutionService executionService,
            PaymentRefundGateway gateway,
            DisputePayoutHoldRepository holdRepository,
            DisputePayoutHoldTransactionService holdService,
            BookingFacade bookingFacade) {
    }
}
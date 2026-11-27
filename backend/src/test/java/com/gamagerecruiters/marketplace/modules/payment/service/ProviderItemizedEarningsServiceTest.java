package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.dto.ItemizedEarningsPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.ItemizedEarningsResponse;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;

/**
 * Verifies provider-scoped itemized earnings retrieval and financial calculations.
 */
class ProviderItemizedEarningsServiceTest {

    private static final String PROVIDER_ID = "provider-123";
    private static final String PAYMENT_ID = "payment-123";
    private static final String BOOKING_ID = "booking-123";
    private static final String CURRENCY = "LKR";

    private static final Instant RECOGNIZED_AT =
            Instant.parse("2026-11-26T10:00:00Z");

    private EarningsLedgerRepository ledgerRepository;
    private PaymentRepository paymentRepository;
    private ProviderItemizedEarningsService service;

    @BeforeEach
    void setUp() {
        ledgerRepository = mock(EarningsLedgerRepository.class);
        paymentRepository = mock(PaymentRepository.class);

        service = new ProviderItemizedEarningsService(
                ledgerRepository,
                paymentRepository,
                new RefundCommissionCalculator());
    }

    @ParameterizedTest
    @CsvSource({
            "0.00,9000.00,0.00",
            "2000.00,7200.00,1800.00",
            "5000.00,4500.00,4500.00",
            "10000.00,0.00,9000.00",
            "3333.33,6000.00,3000.00"
    })
    void calculatesRefundAdjustedEarnings(
            String refunded,
            String expectedNet,
            String expectedAdjustment) {

        Payment payment = payment(refunded);

        stubRecognition(recognition());
        stubPayment(payment);

        ItemizedEarningsPageResponse result =
                service.getItemizedEarnings(PROVIDER_ID, 0, 10);

        assertEquals(1, result.content().size());

        ItemizedEarningsResponse row = result.content().get(0);

        assertEquals(PAYMENT_ID, row.paymentId());
        assertEquals(BOOKING_ID, row.bookingId());
        assertMoney("10000.00", row.grossAmount());
        assertMoney("0.10", row.commissionRate());
        assertMoney("1000.00", row.commissionAmount());
        assertMoney(refunded, row.refundedAmount());
        assertMoney(expectedNet, row.netEarnings());
        assertMoney(expectedAdjustment, row.refundAdjustmentAmount());
        assertEquals(CURRENCY, row.currency());
        assertEquals(RECOGNIZED_AT, row.recognizedAt());

        verify(paymentRepository).findAllById(Set.of(PAYMENT_ID));
    }

    @Test
    void supportsRefundsCompletedBeforeRecognition() {
        EarningsLedgerEntry entry = recognition();
        entry.setAmount(new BigDecimal("7200.00"));

        stubRecognition(entry);
        stubPayment(payment("2000.00"));

        ItemizedEarningsResponse row =
                service.getItemizedEarnings(PROVIDER_ID, 0, 10)
                        .content().get(0);

        assertMoney("7200.00", row.netEarnings());
        assertMoney("1800.00", row.refundAdjustmentAmount());
    }

    @Test
    void supportsAdditionalRefundsAfterRecognition() {
        EarningsLedgerEntry entry = recognition();
        entry.setAmount(new BigDecimal("7200.00"));

        stubRecognition(entry);
        stubPayment(payment("5000.00"));

        ItemizedEarningsResponse row =
                service.getItemizedEarnings(PROVIDER_ID, 0, 10)
                        .content().get(0);

        assertMoney("4500.00", row.netEarnings());
        assertMoney("4500.00", row.refundAdjustmentAmount());
    }

    @Test
    void supportsReleasedPayment() {
        Payment payment = payment("0.00");
        payment.setStatus(PaymentStatus.RELEASED);

        stubRecognition(recognition());
        stubPayment(payment);

        assertMoney("9000.00",
                service.getItemizedEarnings(PROVIDER_ID, 0, 10)
                        .content().get(0).netEarnings());
    }

    @Test
    void pendingRefundDoesNotReduceConfirmedEntitlement() {
        Payment payment = payment("2000.00");
        payment.setStatus(PaymentStatus.REFUND_PENDING);

        stubRecognition(recognition());
        stubPayment(payment);

        assertMoney("7200.00",
                service.getItemizedEarnings(PROVIDER_ID, 0, 10)
                        .content().get(0).netEarnings());
    }

    @Test
    void treatsNullRefundedAmountAsZero() {
        Payment payment = payment("0.00");
        payment.setRefundedAmount(null);

        stubRecognition(recognition());
        stubPayment(payment);

        assertMoney("9000.00",
                service.getItemizedEarnings(PROVIDER_ID, 0, 10)
                        .content().get(0).netEarnings());
    }

    @Test
    void usesPersistedCommissionInsteadOfDefaultRate() {
        Payment payment = payment("0.00");
        payment.setCommissionAmount(new BigDecimal("1500.00"));
        payment.setCommissionRate(new BigDecimal("0.15"));

        EarningsLedgerEntry entry = recognition();
        entry.setAmount(new BigDecimal("8500.00"));

        stubRecognition(entry);
        stubPayment(payment);

        ItemizedEarningsResponse row =
                service.getItemizedEarnings(PROVIDER_ID, 0, 10)
                        .content().get(0);

        assertMoney("1500.00", row.commissionAmount());
        assertMoney("0.15", row.commissionRate());
        assertMoney("8500.00", row.netEarnings());
    }

    @Test
    void usesProviderCurrencyEventTypeAndDeterministicPagination() {
        PageRequest expected = PageRequest.of(
                2, 5,
                Sort.by(
                        Sort.Order.desc("sequenceNumber"),
                        Sort.Order.desc("id")));

        when(ledgerRepository.findByProviderIdAndCurrencyAndEventType(
                PROVIDER_ID, CURRENCY,
                EarningsLedgerEventType.EARNING_PENDING, expected))
                .thenReturn(Page.empty(expected));

        ItemizedEarningsPageResponse result =
                service.getItemizedEarnings(PROVIDER_ID, 2, 5);

        assertTrue(result.content().isEmpty());
        assertEquals(2, result.page());
        assertEquals(5, result.size());
        assertEquals(0, result.totalElements());

        verify(ledgerRepository)
                .findByProviderIdAndCurrencyAndEventType(
                        PROVIDER_ID, CURRENCY,
                        EarningsLedgerEventType.EARNING_PENDING, expected);

        verifyNoInteractions(paymentRepository);
    }

    @Test
    void rejectsOffsetAboveMaximum() {
        int page = 1001;
        int size = 100;
        rejectsInvalidPagination(page, size);
    }

    @Test
    void rejectsOffsetJustAboveMaximum() {
        int page = 100001;
        int size = 1;
        rejectsInvalidPagination(page, size);
    }

    @Test
    void rejectsOffsetThatWouldOverflowInteger() {
        int page = Integer.MAX_VALUE;
        int size = 100;
        rejectsInvalidPagination(page, size);
    }

    @Test
    void acceptsMaximumAllowedOffset() {
        when(ledgerRepository.findByProviderIdAndCurrencyAndEventType(
                eq(PROVIDER_ID), eq(CURRENCY),
                eq(EarningsLedgerEventType.EARNING_PENDING),
                any(Pageable.class)))
                .thenAnswer(invocation ->
                        Page.empty(invocation.getArgument(3, Pageable.class)));

        var response = service.getItemizedEarnings(PROVIDER_ID, 1000, 100);

        assertEquals(1000, response.page());
        assertEquals(100, response.size());
        assertTrue(response.content().isEmpty());
    }

    @Test
    void preservesPaginationMetadata() {
        PageRequest pageable = PageRequest.of(0, 1);

        when(ledgerRepository.findByProviderIdAndCurrencyAndEventType(
                eq(PROVIDER_ID), eq(CURRENCY),
                eq(EarningsLedgerEventType.EARNING_PENDING),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(recognition()), pageable, 12));

        stubPayment(payment("0.00"));

        ItemizedEarningsPageResponse result =
                service.getItemizedEarnings(PROVIDER_ID, 0, 1);

        assertEquals(12, result.totalElements());
        assertEquals(12, result.totalPages());
        assertEquals(1, result.size());

        assertThrows(UnsupportedOperationException.class,
                () -> result.content().clear());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void rejectsMissingProviderIdentity(String providerId) {
        assertThrows(ApiException.class,
                () -> service.getItemizedEarnings(providerId, 0, 10));

        verifyNoInteractions(ledgerRepository, paymentRepository);
    }

    @ParameterizedTest
    @CsvSource({"-1,10", "0,0", "0,-1", "0,101"})
    void rejectsInvalidPagination(int page, int size) {
        assertThrows(ApiException.class,
                () -> service.getItemizedEarnings(PROVIDER_ID, page, size));

        verifyNoInteractions(ledgerRepository, paymentRepository);
    }

    @Test
    void rejectsRecognitionOwnedByAnotherProvider() {
        EarningsLedgerEntry entry = recognition();
        entry.setProviderId("another-provider");

        stubRecognition(entry);

        assertInconsistentData();
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void rejectsRecognitionInUnsupportedCurrency() {
        EarningsLedgerEntry entry = recognition();
        entry.setCurrency("USD");

        stubRecognition(entry);

        assertInconsistentData();
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void rejectsBalanceMovementAsNewIncome() {
        EarningsLedgerEntry entry = recognition();
        entry.setEventType(EarningsLedgerEventType.EARNING_AVAILABLE);

        stubRecognition(entry);

        assertInconsistentData();
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void rejectsNonCanonicalRecognitionKey() {
        EarningsLedgerEntry entry = recognition();
        entry.setEventKey("unexpected-key");

        stubRecognition(entry);

        assertInconsistentData();
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void rejectsDuplicateRecognitionWithinPage() {
        EarningsLedgerEntry first = recognition();
        EarningsLedgerEntry second = recognition();
        second.setSequenceNumber(2L);

        stubRecognition(first, second);

        assertInconsistentData();
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void supportsMixedSequencedAndLegacyRecognitionRecords() {
        EarningsLedgerEntry current = recognition();

        EarningsLedgerEntry legacy = recognition();
        legacy.setId("recognition-legacy");
        legacy.setPaymentId("payment-legacy");
        legacy.setBookingId("booking-legacy");
        legacy.setEventKey("earning-pending:payment-legacy");
        legacy.setSequenceNumber(null);

        Payment currentPayment = payment("0.00");

        Payment legacyPayment = payment("2000.00");
        legacyPayment.setId("payment-legacy");
        legacyPayment.setBookingId("booking-legacy");

        stubRecognition(current, legacy);

        when(paymentRepository.findAllById(any()))
                .thenReturn(List.of(legacyPayment, currentPayment));

        ItemizedEarningsPageResponse result =
                service.getItemizedEarnings(PROVIDER_ID, 0, 10);

        assertEquals(2, result.content().size());
        assertEquals(2L, result.totalElements());
        assertEquals(1, result.totalPages());

        ItemizedEarningsResponse currentRow = result.content().get(0);
        ItemizedEarningsResponse legacyRow = result.content().get(1);

        assertEquals(PAYMENT_ID, currentRow.paymentId());
        assertMoney("9000.00", currentRow.netEarnings());

        assertEquals("payment-legacy", legacyRow.paymentId());
        assertEquals("booking-legacy", legacyRow.bookingId());
        assertEquals(CURRENCY, legacyRow.currency());
        assertEquals(RECOGNIZED_AT, legacyRow.recognizedAt());
        assertMoney("10000.00", legacyRow.grossAmount());
        assertMoney("1000.00", legacyRow.commissionAmount());
        assertMoney("1800.00", legacyRow.refundAdjustmentAmount());
        assertMoney("7200.00", legacyRow.netEarnings());

        assertNull(legacy.getSequenceNumber());

        verify(paymentRepository).findAllById(
                Set.of(PAYMENT_ID, "payment-legacy"));

        verifyNoMoreInteractions(paymentRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsInvalidRecognitionSequence(long sequence) {
        EarningsLedgerEntry entry = recognition();
        entry.setSequenceNumber(sequence);

        stubRecognition(entry);

        assertInconsistentData();
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void rejectsMissingPayment() {
        stubRecognition(recognition());

        when(paymentRepository.findAllById(any()))
                .thenReturn(List.of());

        assertInconsistentData();
    }

    @Test
    void rejectsUnexpectedPaymentReturnedByRepository() {
        Payment payment = payment("0.00");
        payment.setId("unrelated-payment");

        stubRecognition(recognition());
        stubPayment(payment);

        assertInconsistentData();
    }

    @Test
    void rejectsMismatchedBooking() {
        Payment payment = payment("0.00");
        payment.setBookingId("another-booking");

        stubRecognition(recognition());
        stubPayment(payment);

        assertInconsistentData();
    }

    @Test
    void rejectsMismatchedPaymentCurrency() {
        Payment payment = payment("0.00");
        payment.setCurrency("USD");

        stubRecognition(recognition());
        stubPayment(payment);

        assertInconsistentData();
    }

    @Test
    void rejectsUncapturedPayment() {
        Payment payment = payment("0.00");
        payment.setStatus(PaymentStatus.INITIATED);

        stubRecognition(recognition());
        stubPayment(payment);

        assertInconsistentData();
    }

    @Test
    void rejectsMissingCommissionSnapshot() {
        Payment payment = payment("0.00");
        payment.setCommissionAmount(null);

        stubRecognition(recognition());
        stubPayment(payment);

        assertInconsistentData();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1.00", "10000.01"})
    void rejectsInvalidCumulativeRefund(String refunded) {
        stubRecognition(recognition());
        stubPayment(payment(refunded));

        assertInconsistentData();
    }

    @Test
    void rejectsRecognitionAboveOriginalEntitlement() {
        EarningsLedgerEntry entry = recognition();
        entry.setAmount(new BigDecimal("9000.01"));

        stubRecognition(entry);
        stubPayment(payment("0.00"));

        assertInconsistentData();
    }

    @Test
    void rejectsEntitlementAboveRecognizedAmount() {
        EarningsLedgerEntry entry = recognition();
        entry.setAmount(new BigDecimal("7000.00"));

        stubRecognition(entry);
        stubPayment(payment("2000.00"));

        assertInconsistentData();
    }

    /**
     * Creates a canonical committed recognition event.
     */
    private EarningsLedgerEntry recognition() {
        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setId("recognition-123");
        entry.setEventKey("earning-pending:" + PAYMENT_ID);
        entry.setPaymentId(PAYMENT_ID);
        entry.setBookingId(BOOKING_ID);
        entry.setProviderId(PROVIDER_ID);
        entry.setEventType(EarningsLedgerEventType.EARNING_PENDING);
        entry.setAmount(new BigDecimal("9000.00"));
        entry.setCurrency(CURRENCY);
        entry.setSequenceNumber(1L);
        entry.setCreatedAt(RECOGNIZED_AT);

        return entry;
    }

    /**
     * Creates a payment fixture with an original commission snapshot.
     */
    private Payment payment(String refunded) {
        Payment payment = new Payment();

        payment.setId(PAYMENT_ID);
        payment.setBookingId(BOOKING_ID);
        payment.setCurrency(CURRENCY);
        payment.setAmount(new BigDecimal("10000.00"));
        payment.setCommissionAmount(new BigDecimal("1000.00"));
        payment.setCommissionRate(new BigDecimal("0.10"));
        payment.setRefundedAmount(new BigDecimal(refunded));

        BigDecimal refund = new BigDecimal(refunded);

        if (refund.compareTo(payment.getAmount()) >= 0) {
            payment.setStatus(PaymentStatus.REFUNDED);
        } else if (refund.signum() > 0) {
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
        } else {
            payment.setStatus(PaymentStatus.CAPTURED);
        }

        return payment;
    }

    private void stubRecognition(EarningsLedgerEntry... entries) {
        when(ledgerRepository.findByProviderIdAndCurrencyAndEventType(
                eq(PROVIDER_ID), eq(CURRENCY),
                eq(EarningsLedgerEventType.EARNING_PENDING),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(entries),
                        PageRequest.of(0, 10),
                        entries.length));
    }

    private void stubPayment(Payment payment) {
        when(paymentRepository.findAllById(any()))
                .thenReturn(List.of(payment));
    }

    private void assertInconsistentData() {
        assertThrows(ApiException.class,
                () -> service.getItemizedEarnings(PROVIDER_ID, 0, 10));
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}

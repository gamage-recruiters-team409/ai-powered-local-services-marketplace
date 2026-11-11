
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

/**
 * Unit tests for payment initialization, input validation,
 * and commission snapshot calculations.
 *
 * <p>
 * These tests do not require MongoDB or a payment gateway.
 * They verify the payment data before persistence.
 * </p>
 */
class PaymentCreationServiceTest {

    private PaymentCreationService paymentCreationService;

    @BeforeEach
    void setUp() {
        paymentCreationService = new PaymentCreationService(
                new CommissionCalculator());
    }

    /**
     * Verifies that valid details produce an initialized payment.
     */
    @Test
    void shouldCreateValidPayment() {
        Payment payment = paymentCreationService.createPayment(
                "BOOK-001",
                new BigDecimal("10000.00"),
                "LKR",
                new BigDecimal("0.10"));

        assertEquals("BOOK-001", payment.getBookingId());
        assertEquals(0, new BigDecimal("10000.00")
                .compareTo(payment.getAmount()));
        assertEquals("LKR", payment.getCurrency());
        assertEquals(PaymentStatus.INITIATED, payment.getStatus());
    }

    /**
     * Verifies that commission values are captured at creation time.
     */
    @Test
    void shouldStoreCommissionSnapshot() {
        Payment payment = paymentCreationService.createPayment(
                "BOOK-002",
                new BigDecimal("10000.00"),
                "LKR",
                new BigDecimal("0.15"));

        assertEquals(0, new BigDecimal("0.15")
                .compareTo(payment.getCommissionRate()));
        assertEquals(0, new BigDecimal("1500.00")
                .compareTo(payment.getCommissionAmount()));
    }

    @Test
    void shouldRejectZeroPaymentAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> paymentCreationService.createPayment(
                        "BOOK-003",
                        BigDecimal.ZERO,
                        "LKR",
                        new BigDecimal("0.10")));
    }

    @Test
    void shouldRejectNegativePaymentAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> paymentCreationService.createPayment(
                        "BOOK-004",
                        new BigDecimal("-100.00"),
                        "LKR",
                        new BigDecimal("0.10")));
    }

    @Test
    void shouldRejectExcessiveDecimalPlaces() {
        assertThrows(IllegalArgumentException.class,
                () -> paymentCreationService.createPayment(
                        "BOOK-005",
                        new BigDecimal("100.123"),
                        "LKR",
                        new BigDecimal("0.10")));
    }

    @Test
    void shouldRejectBlankBookingId() {
        assertThrows(IllegalArgumentException.class,
                () -> paymentCreationService.createPayment(
                        " ",
                        new BigDecimal("1000.00"),
                        "LKR",
                        new BigDecimal("0.10")));
    }

    @Test
    void shouldRejectInvalidCurrency() {
        assertThrows(IllegalArgumentException.class,
                () -> paymentCreationService.createPayment(
                        "BOOK-006",
                        new BigDecimal("1000.00"),
                        "INVALID",
                        new BigDecimal("0.10")));
    }

    @Test
    void shouldNormalizeCurrencyCode() {
        Payment payment = paymentCreationService.createPayment(
                "BOOK-007",
                new BigDecimal("1000.00"),
                " lkr ",
                new BigDecimal("0.10"));

        assertEquals("LKR", payment.getCurrency());
    }

    @Test
    void shouldRejectInvalidCommissionRate() {
        assertThrows(IllegalArgumentException.class,
                () -> paymentCreationService.createPayment(
                        "BOOK-008",
                        new BigDecimal("1000.00"),
                        "LKR",
                        new BigDecimal("1.50")));
    }

    @Test
    void shouldInitializeRefundAmountToZero() {
        Payment payment = paymentCreationService.createPayment(
                "BOOK-009",
                new BigDecimal("1000.00"),
                "LKR",
                new BigDecimal("0.10"));

        assertEquals(0, BigDecimal.ZERO
                .compareTo(payment.getRefundedAmount()));
    }

    @Test
    void shouldInitializePaymentTimestamps() {
        Payment payment = paymentCreationService.createPayment(
                "BOOK-010",
                new BigDecimal("1000.00"),
                "LKR",
                new BigDecimal("0.10"));

        assertNotNull(payment.getCreatedAt());
        assertNotNull(payment.getUpdatedAt());
        assertEquals(payment.getCreatedAt(), payment.getUpdatedAt());
    }

    @Test
    void shouldTrimBookingId() {
        Payment payment = paymentCreationService.createPayment(
                " BOOK-011 ",
                new BigDecimal("1000.00"),
                "LKR",
                new BigDecimal("0.10"));

        assertEquals("BOOK-011", payment.getBookingId());
    }
}

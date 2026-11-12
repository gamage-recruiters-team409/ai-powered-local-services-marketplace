
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.repository.PaymentRepository;

/**
 * Unit tests for PaymentPersistenceService.
 *
 * <p>
 * Uses a mocked PaymentRepository to verify persistence delegation,
 * input validation, and lookup behavior without connecting to MongoDB.
 * </p>
 */
class PaymentPersistenceServiceTest {

    private PaymentRepository paymentRepository;
    private PaymentPersistenceService paymentPersistenceService;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        paymentPersistenceService = new PaymentPersistenceService(paymentRepository);
    }

    @Test
    void constructorRejectsNullRepository() {
        assertThrows(
                NullPointerException.class,
                () -> new PaymentPersistenceService(null));
    }

    @Test
    void savePaymentDelegatesToRepository() {
        Payment payment = new Payment();
        Payment savedPayment = new Payment();
        savedPayment.setId("payment-001");

        when(paymentRepository.save(payment)).thenReturn(savedPayment);

        Payment result = paymentPersistenceService.savePayment(payment);

        assertSame(savedPayment, result);
        verify(paymentRepository).save(payment);
        verifyNoMoreInteractions(paymentRepository);
    }

    @Test
    void savePaymentRejectsNullPayment() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> paymentPersistenceService.savePayment(null));

        assertEquals("Payment must not be null", exception.getMessage());
        verifyNoInteractions(paymentRepository);
    }

    @Test
    void findPaymentByIdReturnsExistingPayment() {
        Payment payment = new Payment();
        payment.setId("payment-001");

        when(paymentRepository.findById("payment-001"))
                .thenReturn(Optional.of(payment));

        Optional<Payment> result = paymentPersistenceService.findPaymentById("payment-001");

        assertTrue(result.isPresent());
        assertSame(payment, result.get());
        verify(paymentRepository).findById("payment-001");
    }

    @Test
    void findPaymentByIdReturnsEmptyWhenNotFound() {
        when(paymentRepository.findById("missing"))
                .thenReturn(Optional.empty());

        Optional<Payment> result = paymentPersistenceService.findPaymentById("missing");

        assertTrue(result.isEmpty());
        verify(paymentRepository).findById("missing");
    }

    @Test
    void findPaymentByIdTrimsWhitespace() {
        when(paymentRepository.findById("payment-001"))
                .thenReturn(Optional.empty());

        paymentPersistenceService.findPaymentById("  payment-001  ");

        verify(paymentRepository).findById("payment-001");
    }

    @Test
    void findPaymentByIdRejectsNull() {
        assertInvalidIdentifier(
                () -> paymentPersistenceService.findPaymentById(null),
                "Payment ID must not be blank");
    }

    @Test
    void findPaymentByIdRejectsBlank() {
        assertInvalidIdentifier(
                () -> paymentPersistenceService.findPaymentById("   "),
                "Payment ID must not be blank");
    }

    @Test
    void findPaymentsByBookingIdReturnsMultipleAttempts() {
        Payment first = new Payment();
        Payment second = new Payment();

        List<Payment> payments = List.of(first, second);

        when(paymentRepository.findByBookingId("booking-001"))
                .thenReturn(payments);

        List<Payment> result = paymentPersistenceService.findPaymentsByBookingId(
                "booking-001");

        assertSame(payments, result);
        assertEquals(2, result.size());
        verify(paymentRepository).findByBookingId("booking-001");
    }

    @Test
    void findPaymentsByBookingIdReturnsEmptyList() {
        when(paymentRepository.findByBookingId("booking-001"))
                .thenReturn(List.of());

        List<Payment> result = paymentPersistenceService.findPaymentsByBookingId(
                "booking-001");

        assertTrue(result.isEmpty());
        verify(paymentRepository).findByBookingId("booking-001");
    }

    @Test
    void findPaymentsByBookingIdTrimsWhitespace() {
        when(paymentRepository.findByBookingId("booking-001"))
                .thenReturn(List.of());

        paymentPersistenceService.findPaymentsByBookingId(
                "  booking-001  ");

        verify(paymentRepository).findByBookingId("booking-001");
    }

    @Test
    void findPaymentsByBookingIdRejectsNull() {
        assertInvalidIdentifier(
                () -> paymentPersistenceService.findPaymentsByBookingId(null),
                "Booking ID must not be blank");
    }

    @Test
    void findPaymentsByBookingIdRejectsBlank() {
        assertInvalidIdentifier(
                () -> paymentPersistenceService.findPaymentsByBookingId("   "),
                "Booking ID must not be blank");
    }

    @Test
    void findPaymentByGatewayRefReturnsExistingPayment() {
        Payment payment = new Payment();

        when(paymentRepository.findByGatewayRef("gateway-001"))
                .thenReturn(Optional.of(payment));

        Optional<Payment> result = paymentPersistenceService.findPaymentByGatewayRef(
                "gateway-001");

        assertTrue(result.isPresent());
        assertSame(payment, result.get());
        verify(paymentRepository).findByGatewayRef("gateway-001");
    }

    @Test
    void findPaymentByGatewayRefReturnsEmptyWhenNotFound() {
        when(paymentRepository.findByGatewayRef("missing"))
                .thenReturn(Optional.empty());

        Optional<Payment> result = paymentPersistenceService.findPaymentByGatewayRef("missing");

        assertTrue(result.isEmpty());
        verify(paymentRepository).findByGatewayRef("missing");
    }

    @Test
    void findPaymentByGatewayRefTrimsWhitespace() {
        when(paymentRepository.findByGatewayRef("gateway-001"))
                .thenReturn(Optional.empty());

        paymentPersistenceService.findPaymentByGatewayRef(
                "  gateway-001  ");

        verify(paymentRepository).findByGatewayRef("gateway-001");
    }

    @Test
    void findPaymentByGatewayRefRejectsNull() {
        assertInvalidIdentifier(
                () -> paymentPersistenceService.findPaymentByGatewayRef(null),
                "Gateway reference must not be blank");
    }

    @Test
    void findPaymentByGatewayRefRejectsBlank() {
        assertInvalidIdentifier(
                () -> paymentPersistenceService.findPaymentByGatewayRef("   "),
                "Gateway reference must not be blank");
    }

    /**
     * Verifies that invalid identifiers are rejected before any
     * repository operation is performed.
     */
    private void assertInvalidIdentifier(
            org.junit.jupiter.api.function.Executable operation,
            String expectedMessage) {

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                operation);

        assertEquals(expectedMessage, exception.getMessage());
        verifyNoInteractions(paymentRepository);
    }
}

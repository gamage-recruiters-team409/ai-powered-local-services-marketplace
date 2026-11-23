
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;

class BookingFinancialSnapshotServiceTest {

    private PaymentPersistenceService paymentPersistenceService;
    private BookingFacade bookingFacade;
    private BookingFinancialSnapshotService service;

    @BeforeEach
    void setUp() {
        paymentPersistenceService = mock(PaymentPersistenceService.class);
        bookingFacade = mock(BookingFacade.class);

        service = new BookingFinancialSnapshotService(
                paymentPersistenceService, bookingFacade);

        when(bookingFacade.getBookingParticipants("booking-1"))
                .thenReturn(new BookingParticipants(
                        "booking-1", null, null,
                        "customer-1", "provider-1",
                        BookingStatus.DISPUTED, null, null));
    }

    @Test
    void returnsVerifiedFinancialSnapshot() {
        when(paymentPersistenceService.findPaymentsByBookingId("booking-1"))
                .thenReturn(List.of(payment(
                        "1000.00", "150.00", PaymentStatus.CAPTURED)));

        var snapshot = service.getSnapshot("booking-1");

        assertEquals("payment-1", snapshot.paymentId());
        assertEquals("provider-1", snapshot.providerId());
        assertEquals(new BigDecimal("1000.00"), snapshot.capturedAmount());
        assertEquals(new BigDecimal("150.00"), snapshot.commissionAmount());
        assertEquals(new BigDecimal("850.00"), snapshot.providerEarnings());
        assertEquals(BigDecimal.ZERO, snapshot.refundedAmount());
    }

    @Test
    void rejectsMultipleCapturedPayments() {
        when(paymentPersistenceService.findPaymentsByBookingId("booking-1"))
                .thenReturn(List.of(
                        payment("1000.00", "150.00", PaymentStatus.CAPTURED),
                        payment("1000.00", "150.00", PaymentStatus.CAPTURED)));

        assertThrows(IllegalStateException.class,
                () -> service.getSnapshot("booking-1"));
    }

    @Test
    void rejectsCustomerMismatch() {
        Payment payment = payment(
                "1000.00", "150.00", PaymentStatus.CAPTURED);
        payment.setCustomerId("different-customer");

        when(paymentPersistenceService.findPaymentsByBookingId("booking-1"))
                .thenReturn(List.of(payment));

        assertThrows(IllegalStateException.class,
                () -> service.getSnapshot("booking-1"));
    }

    @Test
    void rejectsInvalidCommission() {
        when(paymentPersistenceService.findPaymentsByBookingId("booking-1"))
                .thenReturn(List.of(payment(
                        "1000.00", "1200.00", PaymentStatus.CAPTURED)));

        assertThrows(IllegalStateException.class,
                () -> service.getSnapshot("booking-1"));
    }

    @Test
    void rejectsPaymentWithoutCapture() {
        when(paymentPersistenceService.findPaymentsByBookingId("booking-1"))
                .thenReturn(List.of(payment(
                        "1000.00", "150.00", PaymentStatus.INITIATED)));

        assertThrows(IllegalStateException.class,
                () -> service.getSnapshot("booking-1"));
    }

    private Payment payment(
            String amount, String commission, PaymentStatus status) {

        Payment payment = new Payment();
        payment.setId("payment-1");
        payment.setBookingId("booking-1");
        payment.setCustomerId("customer-1");
        payment.setCurrency("LKR");
        payment.setAmount(new BigDecimal(amount));
        payment.setCommissionAmount(new BigDecimal(commission));
        payment.setStatus(status);

        return payment;
    }
}

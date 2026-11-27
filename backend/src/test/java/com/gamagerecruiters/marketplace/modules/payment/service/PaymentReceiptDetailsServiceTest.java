package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPaymentSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptDetailsResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptResponse;

/**
 * Verifies customer authorization, trusted booking integration,
 * and financial consistency of enriched payment receipts.
 */
class PaymentReceiptDetailsServiceTest {

    private static final String PAYMENT_ID = "payment-123";
    private static final String BOOKING_ID = "booking-123";
    private static final String CUSTOMER_ID = "customer-123";
    private static final String PROVIDER_ID = "provider-123";

    private PaymentRetrievalService retrievalService;
    private BookingFacade bookingFacade;
    private PaymentReceiptDetailsService service;

    @BeforeEach
    void setUp() {
        retrievalService = mock(PaymentRetrievalService.class);
        bookingFacade = mock(BookingFacade.class);

        service = new PaymentReceiptDetailsService(
                retrievalService, bookingFacade);
    }

    /**
     * Creates verified receipt metadata for a captured payment.
     */
    private PaymentReceiptResponse receipt() {
        return new PaymentReceiptResponse(
                "RCT-payment-123",
                PAYMENT_ID,
                BOOKING_ID,
                new BigDecimal("4500.00"),
                "LKR",
                Instant.parse("2026-11-26T10:00:00Z"));
    }

    /**
     * Creates a trusted booking snapshot without database access.
     */
    private BookingPaymentSnapshot booking() {
        BookingPaymentSnapshot snapshot =
                mock(BookingPaymentSnapshot.class);

        when(snapshot.bookingId()).thenReturn(BOOKING_ID);
        when(snapshot.bookingRef()).thenReturn("BK-123");
        when(snapshot.customerId()).thenReturn(CUSTOMER_ID);
        when(snapshot.providerId()).thenReturn(PROVIDER_ID);
        when(snapshot.amount()).thenReturn(
                new BigDecimal("4500.00"));
        when(snapshot.currency()).thenReturn("LKR");

        return snapshot;
    }

    private void stubBooking(BookingPaymentSnapshot snapshot) {
        when(retrievalService.getReceiptForCustomer(
                PAYMENT_ID, CUSTOMER_ID))
                .thenReturn(receipt());

        when(bookingFacade.getPaymentSnapshot(BOOKING_ID))
                .thenReturn(snapshot);
    }

    private PaymentReceiptDetailsResponse retrieve() {
        return service.getDetailsForCustomer(
                PAYMENT_ID, CUSTOMER_ID);
    }

    @Test
    void returnsVerifiedReceiptDetails() {
        stubBooking(booking());

        PaymentReceiptDetailsResponse result = retrieve();

        assertEquals(receipt(), result.receipt());
        assertEquals("BK-123", result.bookingReference());
        assertEquals(PROVIDER_ID, result.providerId());
        assertEquals(1, result.items().size());

        assertEquals(
                "Verified booking payment",
                result.items().get(0).description());

        assertEquals(
                new BigDecimal("4500.00"),
                result.items().get(0).amount());

        // Receipt authorization must precede booking access.
        var order = inOrder(retrievalService, bookingFacade);

        order.verify(retrievalService)
                .getReceiptForCustomer(PAYMENT_ID, CUSTOMER_ID);

        order.verify(bookingFacade)
                .getPaymentSnapshot(BOOKING_ID);
    }

    @Test
    void doesNotFabricateUnavailableDisplayFields() {
        stubBooking(booking());

        PaymentReceiptDetailsResponse result = retrieve();

        assertNull(result.serviceTitle());
        assertNull(result.serviceCategory());
        assertNull(result.customerName());
        assertNull(result.providerName());
        assertNull(result.paymentMethod());
    }

    @Test
    void rejectsUnauthorizedReceiptBeforeBookingLookup() {
        when(retrievalService.getReceiptForCustomer(
                PAYMENT_ID, CUSTOMER_ID))
                .thenThrow(new org.springframework.security.access
                        .AccessDeniedException("Access denied"));

        assertThrows(
                org.springframework.security.access
                        .AccessDeniedException.class,
                this::retrieve);

        verifyNoInteractions(bookingFacade);
    }

    @Test
    void rejectsMissingBookingSnapshot() {
        stubBooking(null);

        assertThrows(ApiException.class, this::retrieve);
    }

    @Test
    void rejectsMismatchedBookingIdentifier() {
        BookingPaymentSnapshot snapshot = booking();

        when(snapshot.bookingId())
                .thenReturn("another-booking");

        stubBooking(snapshot);

        assertThrows(ApiException.class, this::retrieve);
    }

    @Test
    void rejectsMismatchedCustomerIdentifier() {
        BookingPaymentSnapshot snapshot = booking();

        when(snapshot.customerId())
                .thenReturn("another-customer");

        stubBooking(snapshot);

        assertThrows(ApiException.class, this::retrieve);
    }

    @Test
    void rejectsMismatchedPaymentAmount() {
        BookingPaymentSnapshot snapshot = booking();

        when(snapshot.amount())
                .thenReturn(new BigDecimal("5000.00"));

        stubBooking(snapshot);

        assertThrows(ApiException.class, this::retrieve);
    }

    @Test
    void rejectsMismatchedCurrency() {
        BookingPaymentSnapshot snapshot = booking();

        when(snapshot.currency()).thenReturn("USD");

        stubBooking(snapshot);

        assertThrows(ApiException.class, this::retrieve);
    }

    @Test
    void rejectsMissingBookingAmount() {
        BookingPaymentSnapshot snapshot = booking();

        when(snapshot.amount()).thenReturn(null);

        stubBooking(snapshot);

        assertThrows(ApiException.class, this::retrieve);
    }
}

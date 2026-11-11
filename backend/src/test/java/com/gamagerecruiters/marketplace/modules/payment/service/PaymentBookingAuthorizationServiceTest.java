
package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;

/**
 * Unit tests for payment-related booking authorization.
 *
 * <p>
 * BookingFacade is mocked so these tests verify authorization
 * behavior without accessing MongoDB or changing booking records.
 * </p>
 */
class PaymentBookingAuthorizationServiceTest {

    private BookingFacade bookingFacade;
    private PaymentBookingAuthorizationService authorizationService;

    private static final String BOOKING_ID = "booking-123";
    private static final String CUSTOMER_ID = "customer-123";

    @BeforeEach
    void setUp() {
        bookingFacade = mock(BookingFacade.class);
        authorizationService = new PaymentBookingAuthorizationService(bookingFacade);
    }

    /**
     * Creates booking data returned by the mocked BookingFacade.
     */
    private BookingParticipants booking(
            String customerId,
            BookingStatus status) {
        return new BookingParticipants(
                BOOKING_ID,
                "series-123",
                "listing-123",
                customerId,
                "provider-123",
                status);
    }

    @Test
    void constructorRejectsNullBookingFacade() {
        assertThrows(
                NullPointerException.class,
                () -> new PaymentBookingAuthorizationService(null));
    }

    @Test
    void ownershipAllowsMatchingCustomer() {
        BookingParticipants participants = booking(CUSTOMER_ID, BookingStatus.CONFIRMED);

        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(participants);

        BookingParticipants result = authorizationService.requireCustomerOwnership(
                BOOKING_ID,
                CUSTOMER_ID);

        assertSame(participants, result);
        verify(bookingFacade).getBookingParticipants(BOOKING_ID);
    }

    @Test
    void ownershipRejectsDifferentCustomer() {
        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(
                        booking("another-customer", BookingStatus.CONFIRMED));

        SecurityException exception = assertThrows(
                SecurityException.class,
                () -> authorizationService.requireCustomerOwnership(
                        BOOKING_ID,
                        CUSTOMER_ID));

        assertEquals(
                "Customer is not authorized to access this booking",
                exception.getMessage());
    }

    @Test
    void ownershipRejectsNullBookingId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> authorizationService.requireCustomerOwnership(
                        null,
                        CUSTOMER_ID));

        verifyNoInteractions(bookingFacade);
    }

    @Test
    void ownershipRejectsBlankBookingId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> authorizationService.requireCustomerOwnership(
                        "   ",
                        CUSTOMER_ID));

        verifyNoInteractions(bookingFacade);
    }

    @Test
    void ownershipRejectsNullCustomerId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> authorizationService.requireCustomerOwnership(
                        BOOKING_ID,
                        null));

        verifyNoInteractions(bookingFacade);
    }

    @Test
    void ownershipRejectsBlankCustomerId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> authorizationService.requireCustomerOwnership(
                        BOOKING_ID,
                        "  "));

        verifyNoInteractions(bookingFacade);
    }

    @Test
    void ownershipTrimsIdentifiersBeforeVerification() {
        BookingParticipants participants = booking(CUSTOMER_ID, BookingStatus.CONFIRMED);

        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(participants);

        BookingParticipants result = authorizationService.requireCustomerOwnership(
                "  " + BOOKING_ID + "  ",
                "  " + CUSTOMER_ID + "  ");

        assertSame(participants, result);
        verify(bookingFacade).getBookingParticipants(BOOKING_ID);
    }

    @Test
    void ownershipRejectsMissingBookingParticipants() {
        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(null);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> authorizationService.requireCustomerOwnership(
                        BOOKING_ID,
                        CUSTOMER_ID));

        assertEquals(
                "BookingFacade returned no booking participants",
                exception.getMessage());
    }

    @Test
    void initiationAllowsAwaitingPaymentForOwner() {
        BookingParticipants participants = booking(CUSTOMER_ID, BookingStatus.AWAITING_PAYMENT);

        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(participants);

        BookingParticipants result = authorizationService.requirePaymentInitiationStatus(
                BOOKING_ID,
                CUSTOMER_ID);

        assertNotNull(result);
        assertSame(participants, result);
        assertEquals(
                BookingStatus.AWAITING_PAYMENT,
                result.status());
    }

    @Test
    void initiationRejectsPendingProviderBooking() {
        assertInitiationRejectedForStatus(
                BookingStatus.PENDING_PROVIDER);
    }

    @Test
    void initiationRejectsConfirmedBooking() {
        assertInitiationRejectedForStatus(
                BookingStatus.CONFIRMED);
    }

    @Test
    void initiationRejectsExpiredBooking() {
        assertInitiationRejectedForStatus(
                BookingStatus.EXPIRED);
    }

    @Test
    void initiationRejectsCustomerCancelledBooking() {
        assertInitiationRejectedForStatus(
                BookingStatus.CANCELLED_BY_CUSTOMER);
    }

    @Test
    void initiationRejectsProviderCancelledBooking() {
        assertInitiationRejectedForStatus(
                BookingStatus.CANCELLED_BY_PROVIDER);
    }

    @Test
    void initiationRejectsUnauthorizedCustomerEvenWhenAwaitingPayment() {
        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(
                        booking(
                                "another-customer",
                                BookingStatus.AWAITING_PAYMENT));

        assertThrows(
                SecurityException.class,
                () -> authorizationService.requirePaymentInitiationStatus(
                        BOOKING_ID,
                        CUSTOMER_ID));
    }

    @Test
    void initiationRejectsNullCustomerIdBeforeBookingLookup() {
        assertThrows(
                IllegalArgumentException.class,
                () -> authorizationService.requirePaymentInitiationStatus(
                        BOOKING_ID,
                        null));

        verifyNoInteractions(bookingFacade);
    }

    @Test
    void initiationRejectsNullBookingIdBeforeBookingLookup() {
        assertThrows(
                IllegalArgumentException.class,
                () -> authorizationService.requirePaymentInitiationStatus(
                        null,
                        CUSTOMER_ID));

        verifyNoInteractions(bookingFacade);
    }

    /**
     * Verifies that a booking status other than AWAITING_PAYMENT
     * cannot pass payment initiation status validation.
     */
    private void assertInitiationRejectedForStatus(
            BookingStatus status) {
        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(booking(CUSTOMER_ID, status));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> authorizationService.requirePaymentInitiationStatus(
                        BOOKING_ID,
                        CUSTOMER_ID));

        assertEquals(
                "Payment initiation is allowed only when the booking "
                        + "status is AWAITING_PAYMENT",
                exception.getMessage());

        verify(bookingFacade).getBookingParticipants(BOOKING_ID);
    }
}


package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;

/**
 * Unit tests for customer-authorized payment retrieval.
 *
 * <p>
 * Dependencies are mocked to verify authorization, validation,
 * and payment lookup behavior without connecting to MongoDB.
 * </p>
 */
class PaymentRetrievalServiceTest {

    private static final String PAYMENT_ID = "payment-123";
    private static final String BOOKING_ID = "booking-123";
    private static final String CUSTOMER_ID = "customer-123";

    private PaymentPersistenceService persistenceService;
    private PaymentBookingAuthorizationService authorizationService;
    private PaymentRetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        persistenceService = mock(PaymentPersistenceService.class);
        authorizationService = mock(PaymentBookingAuthorizationService.class);

        retrievalService = new PaymentRetrievalService(
                persistenceService,
                authorizationService);
    }

    /**
     * Creates a payment record associated with the test booking.
     */
    private Payment payment() {
        Payment payment = new Payment();
        payment.setId(PAYMENT_ID);
        payment.setBookingId(BOOKING_ID);
        return payment;
    }

    @Test
    void constructorRejectsNullPersistenceService() {
        assertThrows(
                NullPointerException.class,
                () -> new PaymentRetrievalService(
                        null,
                        authorizationService));
    }

    @Test
    void constructorRejectsNullAuthorizationService() {
        assertThrows(
                NullPointerException.class,
                () -> new PaymentRetrievalService(
                        persistenceService,
                        null));
    }

    @Test
    void getPaymentReturnsRecordForAuthorizedCustomer() {
        Payment payment = payment();

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        Payment result = retrievalService.getPaymentForCustomer(
                PAYMENT_ID,
                CUSTOMER_ID);

        assertSame(payment, result);
        verify(authorizationService).requireCustomerOwnership(
                BOOKING_ID,
                CUSTOMER_ID);
    }

    @Test
    void getPaymentReturnsNotFoundWhenPaymentDoesNotExist() {
        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.empty());

        ApiException exception = assertThrows(
                ApiException.class,
                () -> retrievalService.getPaymentForCustomer(
                        PAYMENT_ID,
                        CUSTOMER_ID));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("PAYMENT_NOT_FOUND", exception.getErrorCode());
        verifyNoInteractions(authorizationService);
    }

    @Test
    void getPaymentHidesRecordOwnedByAnotherCustomer() {
        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment()));

        when(authorizationService.requireCustomerOwnership(
                BOOKING_ID,
                CUSTOMER_ID)).thenThrow(new AccessDeniedException("Access denied"));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> retrievalService.getPaymentForCustomer(
                        PAYMENT_ID,
                        CUSTOMER_ID));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("PAYMENT_NOT_FOUND", exception.getErrorCode());
    }

    @Test
    void getPaymentHidesMissingBooking() {
        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment()));

        when(authorizationService.requireCustomerOwnership(
                BOOKING_ID,
                CUSTOMER_ID)).thenThrow(new ApiException(
                        HttpStatus.NOT_FOUND,
                        "BOOKING_NOT_FOUND",
                        "Booking not found"));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> retrievalService.getPaymentForCustomer(
                        PAYMENT_ID,
                        CUSTOMER_ID));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("PAYMENT_NOT_FOUND", exception.getErrorCode());
    }

    @Test
    void getPaymentRejectsMissingBookingIdInPaymentRecord() {
        Payment payment = payment();
        payment.setBookingId(null);

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> retrievalService.getPaymentForCustomer(
                        PAYMENT_ID,
                        CUSTOMER_ID));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(authorizationService);
    }

    @Test
    void getPaymentRejectsBlankBookingIdInPaymentRecord() {
        Payment payment = payment();
        payment.setBookingId("   ");

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        ApiException exception = assertThrows(
                ApiException.class,
                () -> retrievalService.getPaymentForCustomer(
                        PAYMENT_ID,
                        CUSTOMER_ID));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        verifyNoInteractions(authorizationService);
    }

    @Test
    void getPaymentRejectsNullPaymentId() {
        assertBadRequest(
                () -> retrievalService.getPaymentForCustomer(
                        null,
                        CUSTOMER_ID));

        verifyNoInteractions(
                persistenceService,
                authorizationService);
    }

    @Test
    void getPaymentRejectsBlankPaymentId() {
        assertBadRequest(
                () -> retrievalService.getPaymentForCustomer(
                        "  ",
                        CUSTOMER_ID));

        verifyNoInteractions(
                persistenceService,
                authorizationService);
    }

    @Test
    void getPaymentRejectsNullCustomerId() {
        assertBadRequest(
                () -> retrievalService.getPaymentForCustomer(
                        PAYMENT_ID,
                        null));

        verifyNoInteractions(
                persistenceService,
                authorizationService);
    }

    @Test
    void getPaymentRejectsBlankCustomerId() {
        assertBadRequest(
                () -> retrievalService.getPaymentForCustomer(
                        PAYMENT_ID,
                        "  "));

        verifyNoInteractions(
                persistenceService,
                authorizationService);
    }

    @Test
    void getPaymentTrimsIdentifiers() {
        Payment payment = payment();

        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        Payment result = retrievalService.getPaymentForCustomer(
                "  " + PAYMENT_ID + "  ",
                "  " + CUSTOMER_ID + "  ");

        assertSame(payment, result);
        verify(persistenceService).findPaymentById(PAYMENT_ID);
        verify(authorizationService).requireCustomerOwnership(
                BOOKING_ID,
                CUSTOMER_ID);
    }

    @Test
    void getPaymentPropagatesUnexpectedAuthorizationError() {
        when(persistenceService.findPaymentById(PAYMENT_ID))
                .thenReturn(Optional.of(payment()));

        ApiException original = new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "BOOKING_SERVICE_UNAVAILABLE",
                "Booking service unavailable");

        when(authorizationService.requireCustomerOwnership(
                BOOKING_ID,
                CUSTOMER_ID)).thenThrow(original);

        ApiException actual = assertThrows(
                ApiException.class,
                () -> retrievalService.getPaymentForCustomer(
                        PAYMENT_ID,
                        CUSTOMER_ID));

        assertSame(original, actual);
    }

    @Test
    void getPaymentsForBookingReturnsRecordsForOwner() {
        List<Payment> payments = List.of(payment());

        when(persistenceService.findPaymentsByBookingId(BOOKING_ID))
                .thenReturn(payments);

        List<Payment> result = retrievalService.getPaymentsForBooking(
                BOOKING_ID,
                CUSTOMER_ID);

        assertSame(payments, result);
        verify(authorizationService).requireCustomerOwnership(
                BOOKING_ID,
                CUSTOMER_ID);
        verify(persistenceService).findPaymentsByBookingId(BOOKING_ID);
    }

    @Test
    void getPaymentsForBookingReturnsEmptyListWhenNoPaymentsExist() {
        when(persistenceService.findPaymentsByBookingId(BOOKING_ID))
                .thenReturn(List.of());

        List<Payment> result = retrievalService.getPaymentsForBooking(
                BOOKING_ID,
                CUSTOMER_ID);

        assertTrue(result.isEmpty());
        verify(authorizationService).requireCustomerOwnership(
                BOOKING_ID,
                CUSTOMER_ID);
    }

    @Test
    void getPaymentsForBookingRejectsUnauthorizedCustomerBeforeLookup() {
        when(authorizationService.requireCustomerOwnership(
                BOOKING_ID,
                CUSTOMER_ID)).thenThrow(new AccessDeniedException("Access denied"));

        assertThrows(
                AccessDeniedException.class,
                () -> retrievalService.getPaymentsForBooking(
                        BOOKING_ID,
                        CUSTOMER_ID));

        verifyNoInteractions(persistenceService);
    }

    @Test
    void getPaymentsForBookingRejectsNullBookingId() {
        assertBadRequest(
                () -> retrievalService.getPaymentsForBooking(
                        null,
                        CUSTOMER_ID));

        verifyNoInteractions(
                persistenceService,
                authorizationService);
    }

    @Test
    void getPaymentsForBookingRejectsBlankBookingId() {
        assertBadRequest(
                () -> retrievalService.getPaymentsForBooking(
                        "  ",
                        CUSTOMER_ID));

        verifyNoInteractions(
                persistenceService,
                authorizationService);
    }

    @Test
    void getPaymentsForBookingRejectsNullCustomerId() {
        assertBadRequest(
                () -> retrievalService.getPaymentsForBooking(
                        BOOKING_ID,
                        null));

        verifyNoInteractions(
                persistenceService,
                authorizationService);
    }

    @Test
    void getPaymentsForBookingRejectsBlankCustomerId() {
        assertBadRequest(
                () -> retrievalService.getPaymentsForBooking(
                        BOOKING_ID,
                        "  "));

        verifyNoInteractions(
                persistenceService,
                authorizationService);
    }

    @Test
    void getPaymentsForBookingTrimsIdentifiers() {
        when(persistenceService.findPaymentsByBookingId(BOOKING_ID))
                .thenReturn(List.of(payment()));

        retrievalService.getPaymentsForBooking(
                "  " + BOOKING_ID + "  ",
                "  " + CUSTOMER_ID + "  ");

        verify(authorizationService).requireCustomerOwnership(
                BOOKING_ID,
                CUSTOMER_ID);
        verify(persistenceService).findPaymentsByBookingId(BOOKING_ID);
    }

    /**
     * Verifies that invalid input produces the standard payment request error.
     */
    private void assertBadRequest(Runnable action) {
        ApiException exception = assertThrows(
                ApiException.class,
                action::run);

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals(
                "INVALID_PAYMENT_REQUEST",
                exception.getErrorCode());
    }
}

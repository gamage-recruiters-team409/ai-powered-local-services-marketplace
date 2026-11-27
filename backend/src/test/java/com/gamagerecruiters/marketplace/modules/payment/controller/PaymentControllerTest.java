
package com.gamagerecruiters.marketplace.modules.payment.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentPageResponse;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentResponse;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentRetrievalService;
import com.gamagerecruiters.marketplace.modules.payment.service.CustomerRefundRetrievalService;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentReceiptPdfService;

/**
 * Unit tests for the customer-facing payment retrieval controller.
 *
 * <p>
 * Tests call controller methods directly and mock the retrieval service.
 * Authentication is simulated through Spring SecurityContextHolder.
 * </p>
 *
 * <p>
 * HTTP filter-chain behavior and method-level role enforcement
 * must be tested separately using Spring Security integration tests.
 * </p>
 */
class PaymentControllerTest {

    private static final String CUSTOMER_ID = "customer-123";
    private static final String PAYMENT_ID = "payment-123";
    private static final String BOOKING_ID = "booking-456";

    private PaymentRetrievalService retrievalService;
    private CustomerRefundRetrievalService refundRetrievalService;
    private PaymentReceiptPdfService receiptPdfService;
    private PaymentController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        retrievalService = mock(PaymentRetrievalService.class);
        refundRetrievalService = mock(CustomerRefundRetrievalService.class);
        receiptPdfService = mock(PaymentReceiptPdfService.class);
        controller = new PaymentController(
                retrievalService, refundRetrievalService, receiptPdfService);
    }

    @AfterEach
    void tearDown() {
        // Prevent authentication state from leaking into other tests.
        SecurityContextHolder.clearContext();
    }

    /**
     * Creates an authenticated customer principal for controller tests.
     */
    private void authenticateCustomer() {
        UserPrincipal principal = new UserPrincipal(
                CUSTOMER_ID,
                "customer@example.com",
                Role.CUSTOMER);

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities());

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);
    }

    /**
     * Creates a representative payment entity for response mapping.
     */
    private Payment createPayment() {
        Payment payment = new Payment();

        payment.setId(PAYMENT_ID);
        payment.setBookingId(BOOKING_ID);
        payment.setAmount(new BigDecimal("2500.00"));
        payment.setCurrency("LKR");
        payment.setStatus(PaymentStatus.INITIATED);
        payment.setRefundedAmount(new BigDecimal("0.00"));
        payment.setCreatedAt(
                Instant.parse("2026-11-11T17:00:00Z"));

        // These internal fields must not appear in PaymentResponse.
        payment.setGatewayRef("internal-gateway-reference");
        payment.setCommissionRate(new BigDecimal("0.10"));
        payment.setCommissionAmount(new BigDecimal("250.00"));

        return payment;
    }

    @Test
    void getPaymentReturnsOkForAuthenticatedCustomer() {
        authenticateCustomer();

        Payment payment = createPayment();

        when(retrievalService.getPaymentForCustomer(
                PAYMENT_ID,
                CUSTOMER_ID)).thenReturn(payment);

        ResponseEntity<PaymentResponse> response = controller.getPayment(PAYMENT_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(PAYMENT_ID, response.getBody().paymentId());

        verify(retrievalService).getPaymentForCustomer(
                PAYMENT_ID,
                CUSTOMER_ID);
    }

    @Test
    void getPaymentMapsCustomerFacingFields() {
        authenticateCustomer();

        when(retrievalService.getPaymentForCustomer(
                PAYMENT_ID,
                CUSTOMER_ID)).thenReturn(createPayment());

        PaymentResponse response = controller
                .getPayment(PAYMENT_ID)
                .getBody();

        assertNotNull(response);
        assertEquals(BOOKING_ID, response.bookingId());
        assertEquals(new BigDecimal("2500.00"), response.amount());
        assertEquals("LKR", response.currency());
        assertEquals(PaymentStatus.INITIATED, response.status());
        assertEquals(
                new BigDecimal("0.00"),
                response.refundedAmount());
        assertEquals(
                Instant.parse("2026-11-11T17:00:00Z"),
                response.createdAt());
    }

    @Test
    void getPaymentRejectsMissingAuthentication() {
        ApiException exception = assertThrows(
                ApiException.class,
                () -> controller.getPayment(PAYMENT_ID));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals(
                "AUTHENTICATION_REQUIRED",
                exception.getErrorCode());

        verifyNoInteractions(retrievalService);
    }

    @Test
    void getPaymentRejectsAuthenticationWithoutUserPrincipal() {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "plain-string-principal",
                null,
                List.of());

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> controller.getPayment(PAYMENT_ID));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        verifyNoInteractions(retrievalService);
    }

    @Test
    void getPaymentRejectsBlankAuthenticatedUserId() {
        UserPrincipal principal = new UserPrincipal(
                "   ",
                "customer@example.com",
                Role.CUSTOMER);

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities());

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        ApiException exception = assertThrows(
                ApiException.class,
                () -> controller.getPayment(PAYMENT_ID));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        verifyNoInteractions(retrievalService);
    }

    @Test
    void getPaymentPropagatesServiceNotFoundError() {
        authenticateCustomer();

        ApiException original = new ApiException(
                HttpStatus.NOT_FOUND,
                "PAYMENT_NOT_FOUND",
                "Payment not found");

        when(retrievalService.getPaymentForCustomer(
                PAYMENT_ID,
                CUSTOMER_ID)).thenThrow(original);

        ApiException actual = assertThrows(
                ApiException.class,
                () -> controller.getPayment(PAYMENT_ID));

        assertSame(original, actual);
    }

    @Test
    void getPaymentsForBookingReturnsOkWithPaymentList() {
        authenticateCustomer();

        when(retrievalService.getPaymentsForBooking(
                BOOKING_ID,
                CUSTOMER_ID)).thenReturn(List.of(createPayment()));

        ResponseEntity<List<PaymentResponse>> response = controller.getPaymentsForBooking(BOOKING_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals(
                PAYMENT_ID,
                response.getBody().get(0).paymentId());

        verify(retrievalService).getPaymentsForBooking(
                BOOKING_ID,
                CUSTOMER_ID);
    }

    @Test
    void getPaymentsForBookingReturnsEmptyList() {
        authenticateCustomer();

        when(retrievalService.getPaymentsForBooking(
                BOOKING_ID,
                CUSTOMER_ID)).thenReturn(List.of());

        ResponseEntity<List<PaymentResponse>> response = controller.getPaymentsForBooking(BOOKING_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void getPaymentsForBookingRejectsMissingAuthentication() {
        ApiException exception = assertThrows(
                ApiException.class,
                () -> controller.getPaymentsForBooking(BOOKING_ID));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals(
                "AUTHENTICATION_REQUIRED",
                exception.getErrorCode());

        verifyNoInteractions(retrievalService);
    }

    @Test
    void getPaymentsForBookingPropagatesServiceError() {
        authenticateCustomer();

        ApiException original = new ApiException(
                HttpStatus.NOT_FOUND,
                "BOOKING_NOT_FOUND",
                "Booking not found");

        when(retrievalService.getPaymentsForBooking(
                BOOKING_ID,
                CUSTOMER_ID)).thenThrow(original);

        ApiException actual = assertThrows(
                ApiException.class,
                () -> controller.getPaymentsForBooking(BOOKING_ID));

        assertSame(original, actual);
    }

    @Test
    void getPaymentsForBookingMapsMultiplePaymentAttempts() {
        authenticateCustomer();

        Payment firstPayment = createPayment();

        Payment secondPayment = createPayment();
        secondPayment.setId("payment-789");
        secondPayment.setStatus(PaymentStatus.FAILED);

        when(retrievalService.getPaymentsForBooking(
                BOOKING_ID,
                CUSTOMER_ID)).thenReturn(List.of(firstPayment, secondPayment));

        List<PaymentResponse> responses = controller
                .getPaymentsForBooking(BOOKING_ID)
                .getBody();

        assertNotNull(responses);
        assertEquals(2, responses.size());
        assertEquals("payment-123", responses.get(0).paymentId());
        assertEquals("payment-789", responses.get(1).paymentId());
        assertEquals(PaymentStatus.FAILED, responses.get(1).status());
    }


    /**
     * Verifies that the payment history endpoint returns
     * the expected customer-facing pagination structure.
     */
    @Test
    void getMyPaymentsReturnsPaginatedPaymentHistory() {
            authenticateCustomer();

            Page<Payment> paymentPage = new PageImpl<>(
                            List.of(createPayment()),
                            PageRequest.of(0, 10),
                            1);

            when(retrievalService.getPaymentsForCustomer(
                            CUSTOMER_ID, 0, 10))
                            .thenReturn(paymentPage);

            ResponseEntity<PaymentPageResponse> response = controller.getMyPayments(0, 10);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());

            PaymentPageResponse body = response.getBody();

            assertEquals(0, body.page());
            assertEquals(10, body.size());
            assertEquals(1L, body.totalElements());
            assertEquals(1, body.totalPages());

            assertEquals(1, body.content().size());
            assertEquals(
                            PAYMENT_ID,
                            body.content().get(0).paymentId());

            verify(retrievalService).getPaymentsForCustomer(
                            CUSTOMER_ID, 0, 10);
    }

    /**
     * Ensures customer history exposes only PaymentResponse fields
     * and does not return internal gateway or commission details.
     */
    @Test
    void getMyPaymentsMapsCustomerFacingFields() {
            authenticateCustomer();

            Page<Payment> paymentPage = new PageImpl<>(
                            List.of(createPayment()),
                            PageRequest.of(0, 10),
                            1);

            when(retrievalService.getPaymentsForCustomer(
                            CUSTOMER_ID, 0, 10))
                            .thenReturn(paymentPage);

            PaymentPageResponse response = controller
                            .getMyPayments(0, 10)
                            .getBody();

            assertNotNull(response);

            PaymentResponse payment = response.content().get(0);

            assertEquals(PAYMENT_ID, payment.paymentId());
            assertEquals(BOOKING_ID, payment.bookingId());
            assertEquals(
                            new BigDecimal("2500.00"),
                            payment.amount());
            assertEquals("LKR", payment.currency());
            assertEquals(
                            PaymentStatus.INITIATED,
                            payment.status());
            assertEquals(
                            new BigDecimal("0.00"),
                            payment.refundedAmount());
            assertEquals(
                            Instant.parse("2026-11-11T17:00:00Z"),
                            payment.createdAt());
    }

    /**
     * Verifies that an authenticated customer with no payments
     * receives an empty page rather than an error.
     */
    @Test
    void getMyPaymentsReturnsEmptyPage() {
            authenticateCustomer();

            Page<Payment> emptyPage = Page.empty(
                            PageRequest.of(0, 10));

            when(retrievalService.getPaymentsForCustomer(
                            CUSTOMER_ID, 0, 10))
                            .thenReturn(emptyPage);

            ResponseEntity<PaymentPageResponse> response = controller.getMyPayments(0, 10);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().content().isEmpty());
            assertEquals(0L, response.getBody().totalElements());
            assertEquals(0, response.getBody().totalPages());
    }

    /**
     * Verifies that the controller forwards the requested
     * pagination values and the authenticated customer ID.
     */
    @Test
    void getMyPaymentsUsesAuthenticatedCustomerAndRequestedPage() {
            authenticateCustomer();

            Page<Payment> paymentPage = Page.empty(
                            PageRequest.of(2, 20));

            when(retrievalService.getPaymentsForCustomer(
                            CUSTOMER_ID, 2, 20))
                            .thenReturn(paymentPage);

            ResponseEntity<PaymentPageResponse> response = controller.getMyPayments(2, 20);

            assertEquals(HttpStatus.OK, response.getStatusCode());

            verify(retrievalService).getPaymentsForCustomer(
                            CUSTOMER_ID, 2, 20);
    }

    /**
     * Rejects payment history requests when no authenticated
     * customer identity is available.
     */
    @Test
    void getMyPaymentsRejectsMissingAuthentication() {
            ApiException exception = assertThrows(
                            ApiException.class,
                            () -> controller.getMyPayments(0, 10));

            assertEquals(
                            HttpStatus.UNAUTHORIZED,
                            exception.getStatus());

            assertEquals(
                            "AUTHENTICATION_REQUIRED",
                            exception.getErrorCode());

            verifyNoInteractions(retrievalService);
    }

    /**
     * Ensures pagination validation errors are propagated
     * without converting them into successful responses.
     */
    @Test
    void getMyPaymentsPropagatesInvalidPaginationError() {
            authenticateCustomer();

            ApiException original = new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_PAYMENT_PAGINATION",
                            "Page number must not be negative");

            when(retrievalService.getPaymentsForCustomer(
                            CUSTOMER_ID, -1, 10))
                            .thenThrow(original);

            ApiException actual = assertThrows(
                            ApiException.class,
                            () -> controller.getMyPayments(-1, 10));

            assertSame(original, actual);
    }


    @Test
    void downloadsReceiptForAuthenticatedCustomer() {
        authenticateCustomer();

        byte[] pdf = "%PDF-1.7".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);

        when(receiptPdfService.generateForCustomer(
                PAYMENT_ID, CUSTOMER_ID)).thenReturn(pdf);

        ResponseEntity<byte[]> response =
                controller.downloadPaymentReceipt(PAYMENT_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(
                org.springframework.http.MediaType.APPLICATION_PDF,
                response.getHeaders().getContentType());
        assertEquals(pdf.length,
                response.getHeaders().getContentLength());
        assertEquals("no-store",
                response.getHeaders().getCacheControl());
        assertEquals("attachment",
                response.getHeaders().getContentDisposition().getType());
        assertEquals("payment-receipt.pdf",
                response.getHeaders().getContentDisposition().getFilename());
        assertEquals("nosniff",
                response.getHeaders().getFirst("X-Content-Type-Options"));
        assertSame(pdf, response.getBody());

        verify(receiptPdfService).generateForCustomer(
                PAYMENT_ID, CUSTOMER_ID);
    }

    @Test
    void rejectsReceiptDownloadWithoutAuthentication() {
        assertThrows(ApiException.class,
                () -> controller.downloadPaymentReceipt(PAYMENT_ID));

        verifyNoInteractions(receiptPdfService);
    }

    @Test
    void propagatesReceiptOwnershipFailure() {
        authenticateCustomer();

        ApiException failure = new ApiException(
                HttpStatus.NOT_FOUND,
                "PAYMENT_NOT_FOUND",
                "Payment not found");

        when(receiptPdfService.generateForCustomer(
                PAYMENT_ID, CUSTOMER_ID)).thenThrow(failure);

        assertSame(failure, assertThrows(ApiException.class,
                () -> controller.downloadPaymentReceipt(PAYMENT_ID)));
    }

}

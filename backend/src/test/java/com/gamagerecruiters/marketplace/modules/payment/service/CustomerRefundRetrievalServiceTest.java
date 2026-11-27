package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation.RefundStatus;
import com.gamagerecruiters.marketplace.modules.payment.dto.CustomerRefundPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.CustomerRefundResponse.CustomerRefundStatus;
import com.gamagerecruiters.marketplace.modules.payment.repository.RefundOperationRepository;

/**
 * Verifies customer refund history authorization, pagination,
 * status mapping and persisted association integrity.
 */
class CustomerRefundRetrievalServiceTest {

    private static final String PAYMENT_ID = "payment-123";
    private static final String BOOKING_ID = "booking-456";
    private static final String CUSTOMER_ID = "customer-789";

    private PaymentRetrievalService paymentRetrievalService;
    private RefundOperationRepository refundRepository;
    private CustomerRefundRetrievalService service;

    @BeforeEach
    void setUp() {
        paymentRetrievalService = mock(PaymentRetrievalService.class);
        refundRepository = mock(RefundOperationRepository.class);

        service = new CustomerRefundRetrievalService(
                paymentRetrievalService, refundRepository);
    }

    @Test
    void returnsAuthorizedCustomerRefundHistory() {
        authorizePayment();

        RefundOperation refund = refund(
                "refund-001", RefundStatus.SUCCEEDED);

        stubRefunds(List.of(refund));

        CustomerRefundPageResponse response =
                service.getRefundsForCustomer(
                        PAYMENT_ID, CUSTOMER_ID, 0, 10);

        assertEquals(1, response.totalElements());
        assertEquals(1, response.content().size());

        var item = response.content().get(0);

        assertEquals("refund-001", item.refundId());
        assertEquals(PAYMENT_ID, item.paymentId());
        assertEquals(BOOKING_ID, item.bookingId());
        assertEquals(new BigDecimal("1500.00"), item.amount());
        assertEquals("LKR", item.currency());
        assertEquals(CustomerRefundStatus.COMPLETED, item.status());
        assertNotNull(item.completedAt());
    }

    @Test
    void verifiesOwnershipBeforeQueryingRefunds() {
        authorizePayment();
        stubRefunds(List.of());

        service.getRefundsForCustomer(
                PAYMENT_ID, CUSTOMER_ID, 0, 10);

        InOrder order = inOrder(
                paymentRetrievalService, refundRepository);

        order.verify(paymentRetrievalService)
                .getPaymentForCustomer(PAYMENT_ID, CUSTOMER_ID);

        order.verify(refundRepository)
                .findByPaymentId(eq(PAYMENT_ID), any(Pageable.class));
    }

    @Test
    void rejectsUnauthorizedPaymentWithoutQueryingRefunds() {
        when(paymentRetrievalService.getPaymentForCustomer(
                PAYMENT_ID, CUSTOMER_ID))
                .thenThrow(new ApiException(
                        HttpStatus.NOT_FOUND,
                        "PAYMENT_NOT_FOUND",
                        "Payment not found"));

        assertThrows(ApiException.class,
                () -> service.getRefundsForCustomer(
                        PAYMENT_ID, CUSTOMER_ID, 0, 10));

        verifyNoInteractions(refundRepository);
    }

    @Test
    void rejectsOffsetAboveMaximum() {
        int page = 1001;
        int size = 100;
        assertInvalidPagination(page, size);
    }

    @Test
    void rejectsOffsetJustAboveMaximum() {
        int page = 100001;
        int size = 1;
        assertInvalidPagination(page, size);
    }

    @Test
    void rejectsOffsetThatWouldOverflowInteger() {
        int page = Integer.MAX_VALUE;
        int size = 100;
        assertInvalidPagination(page, size);
    }

    @Test
    void acceptsMaximumAllowedOffset() {
        authorizePayment();
        stubRefunds(List.of());

        var response = service.getRefundsForCustomer(PAYMENT_ID, CUSTOMER_ID, 1000, 100);

        assertEquals(1000, response.page());
        assertEquals(100, response.size());
        assertTrue(response.content().isEmpty());
    }

    @Test
    void returnsEmptyPageWhenPaymentHasNoRefunds() {
        authorizePayment();

        when(refundRepository.findByPaymentId(
                eq(PAYMENT_ID), any(Pageable.class)))
                .thenAnswer(invocation ->
                        Page.empty(invocation.getArgument(1, Pageable.class)));

        CustomerRefundPageResponse response =
                service.getRefundsForCustomer(
                        PAYMENT_ID, CUSTOMER_ID, 0, 10);

        assertTrue(response.content().isEmpty());
        assertEquals(0, response.totalElements());
        assertEquals(0, response.totalPages());
        assertEquals(0, response.page());
        assertEquals(10, response.size());
    }

    @Test
    void appliesDeterministicPaginationAndSorting() {
        authorizePayment();
        stubRefunds(List.of());

        service.getRefundsForCustomer(
                PAYMENT_ID, CUSTOMER_ID, 2, 25);

        ArgumentCaptor<Pageable> captor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(refundRepository)
                .findByPaymentId(eq(PAYMENT_ID), captor.capture());

        Pageable pageable = captor.getValue();

        assertEquals(2, pageable.getPageNumber());
        assertEquals(25, pageable.getPageSize());

        assertEquals(
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")),
                pageable.getSort());
    }

    @Test
    void preservesPaginationMetadata() {
        authorizePayment();

        when(refundRepository.findByPaymentId(
                eq(PAYMENT_ID), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(
                        List.of(refund("refund-001", RefundStatus.SUCCEEDED)),
                        invocation.getArgument(1, Pageable.class),
                        21));

        CustomerRefundPageResponse response =
                service.getRefundsForCustomer(
                        PAYMENT_ID, CUSTOMER_ID, 2, 10);

        assertEquals(2, response.page());
        assertEquals(10, response.size());
        assertEquals(21, response.totalElements());
        assertEquals(3, response.totalPages());
    }

    @Test
    void rejectsNegativePage() {
        assertInvalidPagination(-1, 10);
    }

    @Test
    void rejectsZeroPageSize() {
        assertInvalidPagination(0, 0);
    }

    @Test
    void rejectsNegativePageSize() {
        assertInvalidPagination(0, -1);
    }

    @Test
    void rejectsExcessivePageSize() {
        assertInvalidPagination(0, 101);
    }

    @Test
    void acceptsMaximumPageSize() {
        authorizePayment();
        stubRefunds(List.of());

        CustomerRefundPageResponse response =
                service.getRefundsForCustomer(
                        PAYMENT_ID, CUSTOMER_ID, 0, 100);

        assertEquals(100, response.size());
    }

    @Test
    void returnsMultipleRefundOperations() {
        authorizePayment();

        stubRefunds(List.of(
                refund("refund-001", RefundStatus.SUCCEEDED),
                refund("refund-002", RefundStatus.SUCCEEDED)));

        CustomerRefundPageResponse response =
                service.getRefundsForCustomer(
                        PAYMENT_ID, CUSTOMER_ID, 0, 10);

        assertEquals(2, response.totalElements());
        assertEquals("refund-001", response.content().get(0).refundId());
        assertEquals("refund-002", response.content().get(1).refundId());
    }

    @Test
    void mapsAllPersistedRefundStatuses() {
        authorizePayment();

        stubRefunds(List.of(
                refund("refund-001", RefundStatus.PENDING),
                refund("refund-002", RefundStatus.PROCESSING),
                refund("refund-003", RefundStatus.SUCCEEDED),
                refund("refund-004", RefundStatus.FAILED),
                refund("refund-005", RefundStatus.REQUIRES_RECONCILIATION)));

        CustomerRefundPageResponse response =
                service.getRefundsForCustomer(
                        PAYMENT_ID, CUSTOMER_ID, 0, 10);

        assertEquals(
                List.of(
                        CustomerRefundStatus.PROCESSING,
                        CustomerRefundStatus.PROCESSING,
                        CustomerRefundStatus.COMPLETED,
                        CustomerRefundStatus.FAILED,
                        CustomerRefundStatus.UNDER_REVIEW),
                response.content().stream()
                        .map(item -> item.status())
                        .toList());

        assertNull(response.content().get(0).completedAt());
        assertNull(response.content().get(3).completedAt());
    }

    @Test
    void rejectsRefundAssociatedWithDifferentPayment() {
        authorizePayment();

        RefundOperation refund =
                refund("refund-001", RefundStatus.SUCCEEDED);

        when(refund.getPaymentId()).thenReturn("another-payment");

        stubRefunds(List.of(refund));

        assertThrows(ApiException.class,
                () -> service.getRefundsForCustomer(
                        PAYMENT_ID, CUSTOMER_ID, 0, 10));
    }

    @Test
    void rejectsRefundAssociatedWithDifferentBooking() {
        authorizePayment();

        RefundOperation refund =
                refund("refund-001", RefundStatus.SUCCEEDED);

        when(refund.getBookingId()).thenReturn("another-booking");

        stubRefunds(List.of(refund));

        assertThrows(ApiException.class,
                () -> service.getRefundsForCustomer(
                        PAYMENT_ID, CUSTOMER_ID, 0, 10));
    }

    /**
     * Supplies an already authorized payment from the retrieval boundary.
     */
    private void authorizePayment() {
        Payment payment = mock(Payment.class);

        when(payment.getId()).thenReturn(PAYMENT_ID);
        when(payment.getBookingId()).thenReturn(BOOKING_ID);

        when(paymentRetrievalService.getPaymentForCustomer(
                PAYMENT_ID, CUSTOMER_ID))
                .thenReturn(payment);
    }

    /**
     * Supplies a deterministic page without accessing MongoDB.
     */
    private void stubRefunds(List<RefundOperation> refunds) {
        when(refundRepository.findByPaymentId(
                eq(PAYMENT_ID), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(
                        refunds,
                        invocation.getArgument(1, Pageable.class),
                        refunds.size()));
    }

    /**
     * Creates a controlled refund operation fixture.
     */
    private RefundOperation refund(
            String refundId, RefundStatus status) {

        RefundOperation refund = mock(RefundOperation.class);

        Instant createdAt = Instant.parse("2026-11-26T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-11-26T10:05:00Z");

        when(refund.getId()).thenReturn(refundId);
        when(refund.getPaymentId()).thenReturn(PAYMENT_ID);
        when(refund.getBookingId()).thenReturn(BOOKING_ID);
        when(refund.getAmount()).thenReturn(new BigDecimal("1500.00"));
        when(refund.getCurrency()).thenReturn("LKR");
        when(refund.getStatus()).thenReturn(status);
        when(refund.getCreatedAt()).thenReturn(createdAt);
        when(refund.getUpdatedAt()).thenReturn(updatedAt);

        when(refund.getCompletedAt()).thenReturn(
                status == RefundStatus.SUCCEEDED ? updatedAt : null);

        return refund;
    }

    /**
     * Verifies invalid pagination is rejected before data access.
     */
    private void assertInvalidPagination(int page, int size) {
        assertThrows(ApiException.class,
                () -> service.getRefundsForCustomer(
                        PAYMENT_ID, CUSTOMER_ID, page, size));

        verifyNoInteractions(
                paymentRetrievalService, refundRepository);
    }
}

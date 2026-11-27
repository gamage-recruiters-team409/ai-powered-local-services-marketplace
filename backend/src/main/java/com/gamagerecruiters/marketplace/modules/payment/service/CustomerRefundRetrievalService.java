package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;
import com.gamagerecruiters.marketplace.modules.payment.dto.CustomerRefundPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.repository.RefundOperationRepository;

/**
 * Provides read-only refund history for customer-owned payments.
 *
 * <p>Payment ownership is verified before refund records are queried.
 * This service does not initiate refunds or modify financial state.</p>
 */
@Service
public class CustomerRefundRetrievalService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_PAGE_OFFSET = 100_000L;

    private final PaymentRetrievalService paymentRetrievalService;
    private final RefundOperationRepository refundOperationRepository;

    public CustomerRefundRetrievalService(
            PaymentRetrievalService paymentRetrievalService,
            RefundOperationRepository refundOperationRepository) {

        this.paymentRetrievalService =
                Objects.requireNonNull(paymentRetrievalService);

        this.refundOperationRepository =
                Objects.requireNonNull(refundOperationRepository);
    }

    /**
     * Retrieves a bounded refund history for an authorized payment.
     *
     * @param paymentId associated payment identifier
     * @param customerId authenticated customer identifier
     * @param page zero-based page number
     * @param size requested page size
     * @return customer-visible refund history
     */
    public CustomerRefundPageResponse getRefundsForCustomer(
            String paymentId,
            String customerId,
            int page,
            int size) {

        validatePagination(page, size);

        // Reuse the existing customer ownership authorization boundary.
        Payment payment = paymentRetrievalService
                .getPaymentForCustomer(paymentId, customerId);

        // Use the authorized persisted identifier, not raw request input.
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")));

        Page<RefundOperation> refunds = refundOperationRepository
                .findByPaymentId(payment.getId(), pageable);

        // Fail closed if persisted refund associations are inconsistent.
        for (RefundOperation refund : refunds.getContent()) {
            if (!Objects.equals(payment.getId(), refund.getPaymentId())
                    || !Objects.equals(
                            payment.getBookingId(), refund.getBookingId())) {

                throw new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "REFUND_DATA_INCONSISTENT",
                        "Refund history is temporarily unavailable");
            }
        }

        return CustomerRefundPageResponse.from(refunds);
    }

    /**
     * Prevents invalid or excessively large refund history requests.
     */
    private void validatePagination(int page, int size) {

        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE
                || (long) page * size > MAX_PAGE_OFFSET) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PAGINATION",
                    "Page must be non-negative, size must be between 1 and 100, and offset must not exceed 100000");
        }
    }
}

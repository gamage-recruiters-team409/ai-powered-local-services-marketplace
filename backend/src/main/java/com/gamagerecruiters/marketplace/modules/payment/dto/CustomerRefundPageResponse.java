package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;

import com.gamagerecruiters.marketplace.modules.payment.domain.RefundOperation;

/**
 * Paginated customer-visible refund history for an authorized payment.
 *
 * <p>Pagination metadata is derived from the persisted refund query.
 * Each refund operation is mapped to a restricted customer response.</p>
 */
public record CustomerRefundPageResponse(
        List<CustomerRefundResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /**
     * Creates an immutable refund history response.
     */
    public CustomerRefundPageResponse {
        content = List.copyOf(
                Objects.requireNonNull(content, "Content is required"));
    }

    /**
     * Converts an authorized refund operation page into an API response.
     *
     * @param refunds authorized refund operation page
     * @return paginated customer refund history
     */
    public static CustomerRefundPageResponse from(
            Page<RefundOperation> refunds) {

        Objects.requireNonNull(refunds, "Refund page is required");

        return new CustomerRefundPageResponse(
                refunds.getContent().stream()
                        .map(CustomerRefundResponse::from)
                        .toList(),
                refunds.getNumber(),
                refunds.getSize(),
                refunds.getTotalElements(),
                refunds.getTotalPages());
    }
}

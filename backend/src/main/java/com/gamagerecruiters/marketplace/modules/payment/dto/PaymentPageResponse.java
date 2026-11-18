
package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;

/**
 * Customer-facing paginated payment history response.
 *
 * <p>
 * Defines a stable API response contract for the WP01 Customer
 * Payments List integration. It avoids exposing Spring Data's
 * internal pagination representation to frontend consumers.
 * </p>
 *
 * <p>
 * Only PaymentResponse DTOs are returned. Internal payment entity
 * fields such as gateway references and commission details are
 * excluded from the API response.
 * </p>
 *
 * @param content       payment records for the requested page
 * @param page          zero-based page number
 * @param size          requested page size
 * @param totalElements total number of matching payment records
 * @param totalPages    total number of pages
 */
public record PaymentPageResponse(
        List<PaymentResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /**
     * Creates a customer-facing response from a paginated payment result.
     *
     * @param payments page of customer-authorized payment records
     * @return immutable pagination response
     */
    public static PaymentPageResponse from(Page<Payment> payments) {
        Objects.requireNonNull(
                payments,
                "Payment page must not be null");

        List<PaymentResponse> responses = payments.getContent()
                .stream()
                .map(PaymentResponse::from)
                .toList();

        return new PaymentPageResponse(
                responses,
                payments.getNumber(),
                payments.getSize(),
                payments.getTotalElements(),
                payments.getTotalPages());
    }
}

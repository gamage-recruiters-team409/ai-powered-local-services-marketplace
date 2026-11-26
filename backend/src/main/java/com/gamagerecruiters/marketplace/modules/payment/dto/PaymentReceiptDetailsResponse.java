package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Enriched customer receipt data used for document generation.
 *
 * <p>Financial values originate from the verified payment and
 * immutable booking price snapshot. Current account names are
 * supplementary information, not historical identity snapshots.</p>
 */
public record PaymentReceiptDetailsResponse(
        PaymentReceiptResponse receipt,
        String bookingReference,
        String serviceTitle,
        String serviceCategory,
        String customerName,
        String providerName,
        String providerId,
        String paymentMethod,
        List<LineItem> items) {

    /**
     * Creates an immutable receipt details response.
     */
    public PaymentReceiptDetailsResponse {
        Objects.requireNonNull(receipt, "Receipt must not be null");
        items = List.copyOf(
                Objects.requireNonNull(items, "Items must not be null"));
    }

    /**
     * Represents a verified component of the booking price.
     *
     * @param description service or add-on description
     * @param amount captured pricing component
     */
    public record LineItem(
            String description,
            BigDecimal amount) {

        public LineItem {
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException(
                        "Line item description is required");
            }

            if (amount == null || amount.signum() < 0) {
                throw new IllegalArgumentException(
                        "Line item amount must be non-negative");
            }
        }
    }
}

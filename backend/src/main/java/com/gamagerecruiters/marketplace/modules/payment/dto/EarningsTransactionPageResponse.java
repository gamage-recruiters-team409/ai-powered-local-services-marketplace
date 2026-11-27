package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;

/**
 * Paginated response containing provider-owned earnings transactions.
 *
 * <p>Pagination metadata reflects the database query result rather
 * than an in-memory subset of the provider's financial history.</p>
 */
public record EarningsTransactionPageResponse(
        List<EarningsTransactionResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public EarningsTransactionPageResponse {
        content = List.copyOf(
                Objects.requireNonNull(content, "Transaction content is required"));
    }

    /**
     * Converts a database page into a provider-facing response.
     *
     * @param entries the page of authorized ledger entries
     * @return transaction history with pagination metadata
     */
    public static EarningsTransactionPageResponse from(
            Page<EarningsLedgerEntry> entries) {

        Objects.requireNonNull(entries, "Ledger page is required");

        return new EarningsTransactionPageResponse(
                entries.getContent().stream()
                        .map(EarningsTransactionResponse::from)
                        .toList(),
                entries.getNumber(),
                entries.getSize(),
                entries.getTotalElements(),
                entries.getTotalPages());
    }
}

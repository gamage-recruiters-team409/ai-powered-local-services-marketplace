package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.dto.EarningsTransactionPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;

/**
 * Provides read-only access to provider-owned earnings ledger events.
 *
 * <p>Provider identity must originate from the authenticated security
 * context. This service does not modify financial records or balances.</p>
 */
@Service
public class ProviderEarningsTransactionService {

    private static final String SUPPORTED_CURRENCY = "LKR";
    private static final int MAX_PAGE_SIZE = 100;

    private final EarningsLedgerRepository earningsLedgerRepository;

    public ProviderEarningsTransactionService(
            EarningsLedgerRepository earningsLedgerRepository) {

        this.earningsLedgerRepository =
                Objects.requireNonNull(earningsLedgerRepository);
    }

    /**
     * Retrieves a bounded page of the authenticated provider's
     * LKR earnings transactions.
     *
     * @param providerId trusted authenticated provider identifier
     * @param page zero-based page number
     * @param size requested page size
     * @return provider-owned transaction history
     */
    public EarningsTransactionPageResponse getTransactions(
            String providerId,
            int page,
            int size) {

        if (providerId == null || providerId.isBlank()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Authentication is required to access earnings");
        }

        validatePagination(page, size);

        // Use the committed sequence for ordering and ID as a stable
        // tie-breaker, including legacy entries without a sequence.
        PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("sequenceNumber"),
                        Sort.Order.desc("id")));

        Page<EarningsLedgerEntry> entries =
                earningsLedgerRepository.findByProviderIdAndCurrency(
                        providerId,
                        SUPPORTED_CURRENCY,
                        pageable);

        // Fail closed if the persistence result violates query boundaries.
        for (EarningsLedgerEntry entry : entries.getContent()) {
            if (entry == null
                    || !providerId.equals(entry.getProviderId())
                    || !SUPPORTED_CURRENCY.equals(entry.getCurrency())) {

                throw new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "EARNINGS_DATA_INCONSISTENT",
                        "Earnings transaction history is temporarily unavailable");
            }
        }

        return EarningsTransactionPageResponse.from(entries);
    }

    /**
     * Rejects invalid or excessively large pagination requests.
     */
    private void validatePagination(int page, int size) {

        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PAGINATION",
                    "Page must be non-negative and size must be between 1 and 100");
        }
    }
}

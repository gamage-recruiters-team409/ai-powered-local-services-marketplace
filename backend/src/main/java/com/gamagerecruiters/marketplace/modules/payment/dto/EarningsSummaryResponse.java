
package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.math.BigDecimal;
import java.util.Objects;

import com.gamagerecruiters.marketplace.modules.payment.service.EarningsBalanceCalculator.EarningsBalance;

/**
 * Customer-independent response DTO for a provider's earnings summary.
 *
 * <p>
 * Contains financial balance totals for a single currency.
 * Internal ledger records and payment gateway details are not exposed.
 * </p>
 */
public record EarningsSummaryResponse(
        String currency,
        BigDecimal pending,
        BigDecimal available,
        BigDecimal held,
        BigDecimal paidOut) {

    /**
     * Converts a calculated earnings balance into an API response.
     *
     * @param currency currency of the calculated ledger balances
     * @param balance  validated earnings balance
     * @return provider-facing earnings summary
     */
    public static EarningsSummaryResponse from(
            String currency,
            EarningsBalance balance) {

        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException(
                    "Earnings summary currency is required");
        }

        Objects.requireNonNull(
                balance,
                "Earnings balance is required");

        return new EarningsSummaryResponse(
                currency,
                balance.pending(),
                balance.available(),
                balance.held(),
                balance.paidOut());
    }
}

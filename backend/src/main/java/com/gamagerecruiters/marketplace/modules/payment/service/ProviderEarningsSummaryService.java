
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.dto.EarningsSummaryResponse;

/**
 * Provides read-only earnings summaries for marketplace providers.
 *
 * <p>
 * Provider identity must be obtained from the authenticated
 * security context by the calling controller. This service must
 * not be called with an unverified client-supplied provider ID.
 * </p>
 *
 * <p>
 * Balances are calculated from persisted ledger events.
 * This service does not initiate payouts or modify financial records.
 * </p>
 */
@Service
public class ProviderEarningsSummaryService {

    private final EarningsLedgerService earningsLedgerService;
    private final EarningsBalanceCalculator balanceCalculator;

    public ProviderEarningsSummaryService(
            EarningsLedgerService earningsLedgerService,
            EarningsBalanceCalculator balanceCalculator) {

        this.earningsLedgerService = Objects.requireNonNull(earningsLedgerService);

        this.balanceCalculator = Objects.requireNonNull(balanceCalculator);
    }

    /**
     * Calculates the authenticated provider's earnings summary
     * for the requested currency.
     *
     * <p>
     * Only ledger entries matching the requested currency are
     * included. An empty result returns zero balances for that
     * currency.
     * </p>
     *
     * @param providerId authenticated provider identifier
     * @param currency   requested currency, currently LKR only
     * @return provider earnings summary
     */
    public EarningsSummaryResponse getSummary(
            String providerId,
            String currency) {

        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider ID is required");
        }

        if (!"LKR".equals(currency)) {
            throw new IllegalArgumentException(
                    "Only LKR earnings summaries are currently supported");
        }

        List<EarningsLedgerEntry> entries = earningsLedgerService.getProviderHistory(providerId);

        // Keep currency totals separate to avoid mixing monetary units.
        List<EarningsLedgerEntry> currencyEntries = entries.stream()
                .filter(entry -> entry != null
                        && currency.equals(entry.getCurrency()))
                .toList();

        EarningsBalanceCalculator.EarningsBalance balance = balanceCalculator.calculate(currencyEntries);

        return EarningsSummaryResponse.from(currency, balance);
    }
}

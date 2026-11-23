package com.gamagerecruiters.marketplace.modules.provider.dto;

import java.time.Instant;

/**
 * Provider-facing payout response.
 *
 * The full bank account number is never returned by this DTO.
 */
public record ProviderPayoutDetailsResponse(
        String bankName,
        String accountHolderName,
        String maskedAccountNumber,
        boolean configured,
        Instant updatedAt
) {
}

package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutStatus;

public record PayoutResponse(
        String id,
        String requestKey,
        String providerId,
        String currency,
        BigDecimal amount,
        PayoutStatus status,
        String processedBy,
        String transferReference,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant paidAt) {

    public static PayoutResponse from(Payout payout) {
        Objects.requireNonNull(payout, "Payout is required");
        return new PayoutResponse(
                payout.getId(),
                payout.getRequestKey(),
                payout.getProviderId(),
                payout.getCurrency(),
                payout.getAmount(),
                payout.getStatus(),
                payout.getProcessedBy(),
                payout.getTransferReference(),
                payout.getFailureReason(),
                payout.getCreatedAt(),
                payout.getUpdatedAt(),
                payout.getPaidAt());
    }

    public ProviderPayoutResponse toProviderView() {
        return new ProviderPayoutResponse(
                id,
                requestKey,
                currency,
                amount,
                status,
                createdAt,
                updatedAt,
                paidAt);
    }
}

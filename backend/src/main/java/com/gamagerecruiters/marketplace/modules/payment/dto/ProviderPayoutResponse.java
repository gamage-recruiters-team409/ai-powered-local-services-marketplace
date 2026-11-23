package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutStatus;

public record ProviderPayoutResponse(
        String id,
        String requestKey,
        String currency,
        BigDecimal amount,
        PayoutStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant paidAt) {
}

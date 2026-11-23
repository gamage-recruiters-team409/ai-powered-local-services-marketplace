package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;

import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;

public record PayoutPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static PayoutPageResponse<PayoutResponse> adminFrom(Page<Payout> payouts) {
        Objects.requireNonNull(payouts, "Payout page is required");
        return new PayoutPageResponse<>(
                payouts.getContent().stream().map(PayoutResponse::from).toList(),
                payouts.getNumber(),
                payouts.getSize(),
                payouts.getTotalElements(),
                payouts.getTotalPages());
    }

    public static PayoutPageResponse<ProviderPayoutResponse> providerFrom(Page<Payout> payouts) {
        Objects.requireNonNull(payouts, "Payout page is required");
        return new PayoutPageResponse<>(
                payouts.getContent().stream()
                        .map(PayoutResponse::from)
                        .map(PayoutResponse::toProviderView)
                        .toList(),
                payouts.getNumber(),
                payouts.getSize(),
                payouts.getTotalElements(),
                payouts.getTotalPages());
    }
}

package com.gamagerecruiters.marketplace.modules.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record FailPayoutRequest(@NotBlank String reason) {
}

package com.gamagerecruiters.marketplace.modules.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmPayoutRequest(@NotBlank String transferReference) {
}

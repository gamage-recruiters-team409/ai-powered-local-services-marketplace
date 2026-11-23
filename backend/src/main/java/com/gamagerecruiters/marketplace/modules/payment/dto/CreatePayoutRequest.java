package com.gamagerecruiters.marketplace.modules.payment.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreatePayoutRequest(
        @NotBlank String requestKey,
        @NotNull @DecimalMin("5000.00") @Digits(integer = 12, fraction = 2) BigDecimal amount) {
}

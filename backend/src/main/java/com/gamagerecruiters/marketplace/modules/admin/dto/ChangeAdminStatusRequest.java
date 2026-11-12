package com.gamagerecruiters.marketplace.modules.admin.dto;

import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Only ACTIVE and SUSPENDED are accepted; other values are rejected by the service with 400. */
public record ChangeAdminStatusRequest(
        @NotNull(message = "status is required")
        UserStatus status,

        @NotBlank(message = "reason is required")
        @Size(min = 10, max = 500, message = "reason must be between 10 and 500 characters")
        String reason
) {
}

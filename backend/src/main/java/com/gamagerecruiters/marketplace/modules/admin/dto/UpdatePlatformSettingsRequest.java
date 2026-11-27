package com.gamagerecruiters.marketplace.modules.admin.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;

public record UpdatePlatformSettingsRequest(
        @NotNull(message = "version is required")
        Long version,

        @NotNull(message = "values are required")
        Map<String, Object> values
) {
}

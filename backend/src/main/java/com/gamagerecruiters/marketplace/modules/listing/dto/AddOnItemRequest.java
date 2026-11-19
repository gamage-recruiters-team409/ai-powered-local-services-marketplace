package com.gamagerecruiters.marketplace.modules.listing.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddOnItemRequest(
        String addOnId,

        @NotBlank(message = "Add-on title is required")
        @Size(min = 2, max = 80, message = "Add-on title must be between 2 and 80 characters")
        String title,

        @Size(max = 300, message = "Add-on description must not exceed 300 characters")
        String description,

        @NotNull(message = "Add-on extra price is required")
        @DecimalMin(value = "0.01", message = "Add-on extra price must be positive")
        BigDecimal extraPrice,

        @NotNull(message = "Add-on extra duration is required")
        @Min(value = 0, message = "Add-on extra duration cannot be negative")
        @Max(value = 480, message = "Add-on extra duration cannot exceed 480 minutes")
        Integer extraDurationMin,

        Boolean active
) {
    public AddOnItemRequest(
            String addOnId,
            String title,
            BigDecimal extraPrice,
            Integer extraDurationMin,
            Boolean active
    ) {
        this(addOnId, title, null, extraPrice, extraDurationMin, active);
    }
}

package com.gamagerecruiters.marketplace.modules.catalog.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank(message = "Category name is required")
        @Size(min = 2, max = 100, message = "Category name must be between 2 and 100 characters")
        String name,

        @NotBlank(message = "URL slug is required")
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug must contain only lowercase letters, numbers, and hyphens")
        String slug,

        @Size(max = 500, message = "Description cannot exceed 500 characters")
        String description,

        String icon,

        String parentId,

        @DecimalMin(value = "0.00", message = "Commission rate cannot be negative")
        @DecimalMax(value = "0.50", message = "Commission rate cannot exceed 50%")
        BigDecimal commissionRate
) {
}


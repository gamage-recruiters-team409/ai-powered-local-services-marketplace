package com.gamagerecruiters.marketplace.modules.listing.dto;

import java.math.BigDecimal;
import java.util.List;

import com.gamagerecruiters.marketplace.modules.listing.domain.ExecutionModel;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload to create a service listing.
 * Step 2/3 fields (pricingModel, basePrice, durationMin, executionModel, baseCity, location,
 * coverageRadiusKm) may be omitted when {@code saveAsDraft = true} and are strictly validated in
 * the service layer whenever publishing ({@code saveAsDraft != true}).
 */
public record CreateListingRequest(
        @NotBlank(message = "Category ID is required")
        String categoryId,

        @NotBlank(message = "Title is required")
        @Size(max = 120, message = "Title must not exceed 120 characters")
        String title,

        @Size(max = 3000, message = "Description must not exceed 3000 characters")
        String description,

        PricingModel pricingModel,

        @DecimalMin(value = "100.00", message = "Base price must be at least LKR 100.00")
        BigDecimal basePrice,

        @Min(value = 15, message = "Duration must be at least 15 minutes")
        @Max(value = 1440, message = "Duration cannot exceed 1440 minutes")
        Integer durationMin,

        ExecutionModel executionModel,

        @NotBlank(message = "District is required")
        String district,

        @Size(max = 100, message = "Base city must not exceed 100 characters")
        String baseCity,

        @Valid
        GeoPointDto location,

        @Min(value = 0, message = "Coverage radius must be between 0 and 50 km")
        @Max(value = 50, message = "Coverage radius must be between 0 and 50 km")
        Integer coverageRadiusKm,

        @Size(max = 20, message = "Service areas must not exceed 20 entries")
        List<@NotBlank(message = "Service area entries must not be blank") @Size(max = 100, message = "Service area name must not exceed 100 characters") String> serviceAreas,

        @Size(max = 8, message = "A listing can contain at most 8 images")
        List<@NotBlank(message = "Image URL must not be blank") String> images,

        String coverImageUrl,

        @Size(max = 10, message = "A listing can have at most 10 add-ons")
        List<@Valid AddOnItemRequest> addOns,

        Boolean saveAsDraft
) {
}

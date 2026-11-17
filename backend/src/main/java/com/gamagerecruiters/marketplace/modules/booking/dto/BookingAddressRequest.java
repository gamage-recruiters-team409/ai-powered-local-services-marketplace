package com.gamagerecruiters.marketplace.modules.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BookingAddressRequest(
        @NotBlank(message = "address.line1 is required")
        @Size(max = 120, message = "address.line1 must be at most 120 characters")
        String line1,

        @Size(max = 120, message = "address.line2 must be at most 120 characters")
        String line2,

        @NotBlank(message = "address.city is required")
        @Size(max = 60, message = "address.city must be at most 60 characters")
        String city,

        @NotBlank(message = "address.district is required")
        @Size(max = 60, message = "address.district must be at most 60 characters")
        String district,

        @Size(max = 10, message = "address.postalCode must be at most 10 characters")
        String postalCode
) {
}

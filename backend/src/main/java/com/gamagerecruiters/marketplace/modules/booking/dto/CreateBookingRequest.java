package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Customer booking request. The customer is taken from the logged-in user, never from the body.
 * {@code slotStart} is the UTC {@code start} of a slot returned by the available-slots API.
 */
public record CreateBookingRequest(
        @NotBlank(message = "providerId is required")
        String providerId,

        @NotBlank(message = "listingId is required")
        String listingId,

        @NotNull(message = "slotStart is required")
        Instant slotStart,

        @NotNull(message = "durationMinutes is required")
        @Min(value = 15, message = "durationMinutes must be between 15 and 480")
        @Max(value = 480, message = "durationMinutes must be between 15 and 480")
        Integer durationMinutes,

        @NotNull(message = "address is required")
        @Valid
        BookingAddressRequest address,

        @Size(max = 500, message = "notes must be at most 500 characters")
        String notes
) {
}

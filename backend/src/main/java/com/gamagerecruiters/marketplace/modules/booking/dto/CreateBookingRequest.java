package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Customer booking request. The customer is taken from the logged-in user, never from the body.
 * {@code slotStart} is the UTC {@code start} of a slot returned by the available-slots API.
 * Duration and price are never taken from the body: they come from the listing (WP03) at creation.
 */
public record CreateBookingRequest(
        @NotBlank(message = "providerId is required")
        String providerId,

        @NotBlank(message = "listingId is required")
        String listingId,

        @NotNull(message = "slotStart is required")
        Instant slotStart,

        @Size(max = 10, message = "at most 10 add-ons can be selected")
        List<@NotBlank(message = "addOnIds must not contain blank values") String> addOnIds,

        @NotNull(message = "address is required")
        @Valid
        BookingAddressRequest address,

        @Size(max = 500, message = "notes must be at most 500 characters")
        String notes
) {

    public CreateBookingRequest {
        addOnIds = addOnIds == null ? List.of() : List.copyOf(addOnIds);
    }
}

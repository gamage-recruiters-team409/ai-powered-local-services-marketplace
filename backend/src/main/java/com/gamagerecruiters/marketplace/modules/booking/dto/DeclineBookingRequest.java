package com.gamagerecruiters.marketplace.modules.booking.dto;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingDeclineReason;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Provider declines a request: reason from the dropdown; {@code note} only for OTHER (C-09). */
public record DeclineBookingRequest(
        @NotNull(message = "reason is required")
        BookingDeclineReason reason,

        @Size(max = 300, message = "note must be at most 300 characters")
        String note
) {
}

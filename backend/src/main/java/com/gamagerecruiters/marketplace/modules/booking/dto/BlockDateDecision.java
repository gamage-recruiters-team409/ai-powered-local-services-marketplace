package com.gamagerecruiters.marketplace.modules.booking.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One confirmed booking's decision. {@code newSlotStart} is required for REQUEST_NEW_TIME. */
public record BlockDateDecision(
        @NotBlank String bookingId,
        @NotNull BlockDateAction action,
        Instant newSlotStart,
        @Size(max = 300) String note
) {
}

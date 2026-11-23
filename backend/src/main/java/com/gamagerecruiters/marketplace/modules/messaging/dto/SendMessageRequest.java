package com.gamagerecruiters.marketplace.modules.messaging.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank(message = "Message body cannot be blank")
        @Size(max = 2000, message = "Message body cannot exceed 2000 characters")
        String body
) {
}

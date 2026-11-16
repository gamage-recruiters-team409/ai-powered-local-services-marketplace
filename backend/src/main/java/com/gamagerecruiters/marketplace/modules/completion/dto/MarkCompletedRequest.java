package com.gamagerecruiters.marketplace.modules.completion.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MarkCompletedRequest(
        @Size(max = 500, message = "Completion note must not exceed 500 characters")
        String completionNote,

        @Size(max = 5, message = "A maximum of 5 photos can be attached")
        List<@NotBlank(message = "Photo URL must not be blank") String> photoUrls
) {
}

package com.gamagerecruiters.marketplace.common.dto;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldErrorItem> fieldErrors
) {
    public ErrorResponse(int status, String error, String message, String path) {
        this(Instant.now(), status, error, message, path, List.of());
    }

    public ErrorResponse(int status, String error, String message, String path, List<FieldErrorItem> fieldErrors) {
        this(Instant.now(), status, error, message, path, fieldErrors == null ? List.of() : fieldErrors);
    }
}

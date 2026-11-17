package com.gamagerecruiters.marketplace.modules.verification.dto;

import java.time.Instant;

public record VerificationDocumentAccessResponse(
        String documentId,
        String originalFileName,
        String format,
        String accessUrl,
        Instant expiresAt
) {
}
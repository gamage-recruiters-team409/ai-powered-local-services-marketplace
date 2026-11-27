package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceType;

/**
 * An uploaded evidence file as the parties and admins see it. No storage reference and no URL: a file is opened
 * through {@code GET /disputes/{id}/evidence/{evidenceId}/url}, which returns a short-lived signed link.
 */
public record EvidenceView(
        String id,
        String fileName,
        EvidenceType type,
        String contentType,
        long sizeBytes,
        Role uploadedByRole,
        Instant uploadedAt
) {
}

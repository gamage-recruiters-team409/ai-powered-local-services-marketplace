package com.gamagerecruiters.marketplace.modules.dispute.dto;

import java.time.Instant;

/** A short-lived signed link to one evidence file; it stops working at {@code expiresAt}. Never stored or logged. */
public record EvidenceUrlResponse(String url, Instant expiresAt) {
}

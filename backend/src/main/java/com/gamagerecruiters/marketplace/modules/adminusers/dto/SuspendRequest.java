package com.gamagerecruiters.marketplace.modules.adminusers.dto;

/**
 * Body of POST /{id}/suspend. {@code reasonCode}: REPEATED_CANCELLATIONS | POLICY_VIOLATION | SUSPECTED_FRAUD |
 * ABUSIVE_BEHAVIOUR | OFF_PLATFORM_CONTACT | OTHER (required). {@code note}: optional, at most 500 characters;
 * required (10 to 500) when the reason is OTHER. Checked by the service so a rejected value is never echoed or logged.
 */
public record SuspendRequest(String reasonCode, String note) {
}

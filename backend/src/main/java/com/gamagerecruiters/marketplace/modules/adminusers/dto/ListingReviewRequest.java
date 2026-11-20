package com.gamagerecruiters.marketplace.modules.adminusers.dto;

/**
 * Body of POST /{id}/listings/{listingId}/review. {@code decision}: KEEP_HIDDEN | MAKE_VISIBLE (required).
 * {@code note}: required, 10 to 500 characters after trimming.
 */
public record ListingReviewRequest(String decision, String note) {

    public enum Decision { KEEP_HIDDEN, MAKE_VISIBLE }
}

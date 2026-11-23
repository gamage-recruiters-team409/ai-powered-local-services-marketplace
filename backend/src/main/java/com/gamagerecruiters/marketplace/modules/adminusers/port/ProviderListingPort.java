package com.gamagerecruiters.marketplace.modules.adminusers.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * A provider's listings (WP03, listing module). Real adapter: ListingService. Read-only for the account page, plus the
 * one agreed write: hiding / restoring the provider's listings when the account is suspended / reactivated.
 * Listing moderation (approve / reject flagged listings) is not here: it stays in the MODERATOR workflow.
 */
public interface ProviderListingPort {

    /** Number of the provider's listings per status. */
    record ListingCounts(long total, long active, long paused, long draft, long flagged, long archived,
                         long rejected) {
    }

    /** One row of the account page's listings section. */
    record ListingRow(String id, String title, String categoryName, BigDecimal basePrice, String pricingModel,
                      String status, List<String> moderationFlags, Instant updatedAt) {
    }

    /**
     * One listing for the read-only "View listing details" modal. {@code moderatedAt} / {@code moderationReason} are
     * the moderation time and reason (null when there was none, never guessed); {@code moderationFlags} the automatic
     * screening flags.
     */
    record ListingDetail(String id, String providerId, String title, String categoryName, BigDecimal basePrice,
                         String pricingModel, String status, Instant moderatedAt, Instant lastUpdatedAt,
                         String moderationReason, List<String> moderationFlags, String description, String imageUrl) {
    }

    ListingCounts counts(String providerUserId);

    /** Most recently updated first. */
    Page<ListingRow> listings(String providerUserId, Pageable pageable);

    /**
     * The listing, or empty when the source cannot provide it.
     *
     * @throws com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException if it does not exist
     */
    Optional<ListingDetail> listingDetail(String listingId);

    /**
     * {@code true}: the provider was suspended, hide their active listings. {@code false}: the provider is ACTIVE
     * again, restore the listings hidden by the suspension. Idempotent.
     */
    void updateSuspensionVisibility(String providerUserId, boolean suspended);
}

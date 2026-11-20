package com.gamagerecruiters.marketplace.modules.adminusers.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** A provider's listings that are not publicly visible (WP03, listing module). Real adapter: ListingService. */
public interface ProviderListingPort {

    record HiddenListing(String id, String title, String status, List<String> moderationFlags, Instant createdAt) {
    }

    /** {@code flagged}: listings flagged by moderation (shown first in {@code recentFlagged}). */
    record HiddenListings(long flagged, long rejected, long paused, List<HiddenListing> recentFlagged) {
    }

    /**
     * One listing for the review modal. {@code hiddenSince} is the moderation time and is null when the listing
     * module has not recorded one (it is never guessed from other dates); {@code lastUpdatedAt} is the listing's
     * own last change. {@code hiddenReason} is the moderation reason, if any; {@code moderationFlags} the automatic
     * screening flags.
     */
    record ListingDetail(String id, String providerId, String title, String categoryName, BigDecimal basePrice,
                         String pricingModel, String status, Instant hiddenSince, Instant lastUpdatedAt,
                         String hiddenReason, List<String> moderationFlags, String description, String imageUrl) {
    }

    enum ReviewDecision { KEEP_HIDDEN, MAKE_VISIBLE }

    Optional<HiddenListings> hiddenListings(String providerUserId, int limit);

    /**
     * The listing, or empty when the source cannot provide it.
     *
     * @throws com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException if it does not exist
     */
    Optional<ListingDetail> listingDetail(String listingId);

    /** False while an admin listing decision cannot be applied (stub): the API answers 503 and changes nothing. */
    boolean supportsListingReview();

    void reviewListing(String actorId, String listingId, ReviewDecision decision, String note);
}

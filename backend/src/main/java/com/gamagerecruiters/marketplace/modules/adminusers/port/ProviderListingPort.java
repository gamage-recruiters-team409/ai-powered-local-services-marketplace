package com.gamagerecruiters.marketplace.modules.adminusers.port;

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

    Optional<HiddenListings> hiddenListings(String providerUserId, int limit);
}

package com.gamagerecruiters.marketplace.modules.listing.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Service listing lifecycle statuses and role-scoped state transition rules (WP03).
 *
 * <p>Retains {@code ACTIVE}, {@code PAUSED}, {@code ARCHIVED}, and {@code DRAFT} compatibility
 * with customer favourites and listing lookups (FR-CUST-005), alongside content moderation
 * states ({@code FLAGGED}, {@code REJECTED}).
 */
public enum ListingStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    FLAGGED,
    ARCHIVED,
    REJECTED;

    /**
     * Statuses visible to customers via direct listing lookup / favourites (FR-CUST-005),
     * where {@code PAUSED} and {@code ARCHIVED} render as read-only unavailable cards.
     */
    public static final Set<ListingStatus> FAVOURITE_READABLE_STATUSES =
            Collections.unmodifiableSet(EnumSet.of(ACTIVE, PAUSED, ARCHIVED));

    /**
     * Statuses that prevent soft-deactivation of the listing's parent category (FR-LIST-001).
     */
    public static final Set<ListingStatus> CATEGORY_BLOCKING_STATUSES =
            Collections.unmodifiableSet(EnumSet.of(ACTIVE, PAUSED, FLAGGED));

    private static final Map<ListingStatus, Set<ListingStatus>> PROVIDER_TRANSITIONS =
            new EnumMap<>(ListingStatus.class);

    private static final Map<ListingStatus, Set<ListingStatus>> MODERATOR_TRANSITIONS =
            new EnumMap<>(ListingStatus.class);

    static {
        PROVIDER_TRANSITIONS.put(DRAFT, EnumSet.of(ACTIVE, ARCHIVED));
        PROVIDER_TRANSITIONS.put(ACTIVE, EnumSet.of(PAUSED, ARCHIVED));
        PROVIDER_TRANSITIONS.put(PAUSED, EnumSet.of(ACTIVE, ARCHIVED));
        PROVIDER_TRANSITIONS.put(FLAGGED, EnumSet.of(ARCHIVED));
        PROVIDER_TRANSITIONS.put(REJECTED, EnumSet.of(ARCHIVED));
        PROVIDER_TRANSITIONS.put(ARCHIVED, EnumSet.noneOf(ListingStatus.class));

        MODERATOR_TRANSITIONS.put(FLAGGED, EnumSet.of(ACTIVE, REJECTED));
        for (ListingStatus status : values()) {
            PROVIDER_TRANSITIONS.putIfAbsent(status, EnumSet.noneOf(ListingStatus.class));
            MODERATOR_TRANSITIONS.putIfAbsent(status, EnumSet.noneOf(ListingStatus.class));
        }
    }

    public boolean canProviderTransitionTo(ListingStatus target) {
        return target != null && PROVIDER_TRANSITIONS.get(this).contains(target);
    }

    public boolean canModeratorTransitionTo(ListingStatus target) {
        return target != null && MODERATOR_TRANSITIONS.get(this).contains(target);
    }

    public Set<ListingStatus> allowedProviderTransitions() {
        return Collections.unmodifiableSet(PROVIDER_TRANSITIONS.get(this));
    }

    public Set<ListingStatus> allowedModeratorTransitions() {
        return Collections.unmodifiableSet(MODERATOR_TRANSITIONS.get(this));
    }

    public boolean isFavouriteReadable() {
        return FAVOURITE_READABLE_STATUSES.contains(this);
    }

    public boolean isBookable() {
        return this == ACTIVE;
    }

    public boolean isProviderEditable() {
        return this != ARCHIVED;
    }

    public boolean isFinal() {
        return this == ARCHIVED;
    }
}

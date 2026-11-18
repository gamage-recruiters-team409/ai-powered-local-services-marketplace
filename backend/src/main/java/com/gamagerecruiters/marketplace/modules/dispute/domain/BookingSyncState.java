package com.gamagerecruiters.marketplace.modules.dispute.domain;

/**
 * Internal: whether a dispute's booking has been moved to DISPUTED. Not a dispute status and never shown to users.
 *
 * <ul>
 *   <li>{@link #PENDING}: intent record. The dispute is saved but the booking change is not confirmed yet. Invisible
 *       in every list and detail view, but it still holds the booking's unique active-dispute key.</li>
 *   <li>{@link #SYNCED}: the booking is DISPUTED; the dispute is a normal, visible case.</li>
 * </ul>
 *
 * Records created before this field existed have no value and are treated as SYNCED.
 */
public enum BookingSyncState {
    PENDING,
    SYNCED
}

package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Dispute lifecycle status.
 *
 * <p><b>PROPOSED values, NOT yet approved by the Team Lead.</b> Keep every status in this one enum so a
 * change after review touches a single place.</p>
 *
 * <ul>
 *   <li>{@link #OPEN}: reserved for a dispute that is recorded but not yet sent to the other party
 *       (not used by the current flow; new disputes start as {@link #AWAITING_RESPONSE}).</li>
 *   <li>{@link #AWAITING_RESPONSE}: waiting for the other party's response, until {@code responseDueAt}.</li>
 *   <li>{@link #UNDER_REVIEW}: the other party responded (or the window passed); an admin handles it.</li>
 *   <li>{@link #RESOLVED}: an admin decided; the appeal window is still open.</li>
 *   <li>{@link #RESOLVED_FINAL}: decision is final (appeal window passed or appeal decided).</li>
 *   <li>{@link #CLOSED}: closed without a decision (for example withdrawn or invalid).</li>
 * </ul>
 */
public enum DisputeStatus {
    OPEN,
    AWAITING_RESPONSE,
    UNDER_REVIEW,
    RESOLVED,
    RESOLVED_FINAL,
    CLOSED;

    /**
     * Statuses that still block a new dispute for the same booking. RESOLVED is included because the
     * decision can still be appealed. (Assumption, to be confirmed by the Team Lead.)
     */
    public static final Set<DisputeStatus> ACTIVE = EnumSet.of(OPEN, AWAITING_RESPONSE, UNDER_REVIEW, RESOLVED);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}

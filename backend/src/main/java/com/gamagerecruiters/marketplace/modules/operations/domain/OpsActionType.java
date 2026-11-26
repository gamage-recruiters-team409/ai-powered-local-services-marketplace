package com.gamagerecruiters.marketplace.modules.operations.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Actions an admin can record against an operational case (WP09, TL decisions 2026-11-26).
 *
 * <p>Every action is recorded in {@code ops_case_actions}. {@link #SUSPEND} additionally calls the existing Admin Users
 * suspend flow. There is no WARNING action (the platform cannot notify the user yet — WP06), no BAN and no timed
 * suspension.</p>
 *
 * <ul>
 *   <li><b>SUPPORT_ADMIN and ADMIN</b>: REVIEWED, FLAG_CONCERN, CONTACT_PROVIDER, CONTACT_CUSTOMER, ESCALATE,
 *       MARK_RESOLVED (all case types) and RECOMMEND_SUSPENSION (repeat cancellations only).</li>
 *   <li><b>ADMIN only</b>: SUSPEND (repeat cancellations only).</li>
 * </ul>
 */
public enum OpsActionType {

    REVIEWED(OpsCaseStatus.REVIEWED, false, EnumSet.allOf(OpsCaseType.class)),
    FLAG_CONCERN(OpsCaseStatus.FLAGGED, false, EnumSet.allOf(OpsCaseType.class)),
    CONTACT_PROVIDER(OpsCaseStatus.CONTACTED, false, EnumSet.allOf(OpsCaseType.class)),
    CONTACT_CUSTOMER(OpsCaseStatus.CONTACTED, false, EnumSet.allOf(OpsCaseType.class)),
    ESCALATE(OpsCaseStatus.ESCALATED, false, EnumSet.allOf(OpsCaseType.class)),
    MARK_RESOLVED(OpsCaseStatus.RESOLVED, false, EnumSet.allOf(OpsCaseType.class)),
    RECOMMEND_SUSPENSION(OpsCaseStatus.SUSPENSION_RECOMMENDED, false,
            EnumSet.of(OpsCaseType.REPEAT_CANCELLATION)),
    SUSPEND(OpsCaseStatus.SUSPENDED, true, EnumSet.of(OpsCaseType.REPEAT_CANCELLATION));

    private final OpsCaseStatus resultingStatus;
    private final boolean adminOnly;
    private final Set<OpsCaseType> caseTypes;

    OpsActionType(OpsCaseStatus resultingStatus, boolean adminOnly, Set<OpsCaseType> caseTypes) {
        this.resultingStatus = resultingStatus;
        this.adminOnly = adminOnly;
        this.caseTypes = caseTypes;
    }

    /** The case status after this action is recorded. */
    public OpsCaseStatus resultingStatus() {
        return resultingStatus;
    }

    /** True when only the ADMIN area may perform it (SUPPORT_ADMIN gets 403). */
    public boolean adminOnly() {
        return adminOnly;
    }

    /** True when this action applies to the given case type. */
    public boolean appliesTo(OpsCaseType caseType) {
        return caseTypes.contains(caseType);
    }
}

package com.gamagerecruiters.marketplace.modules.dispute.dto;

/** Where the dispute stands regarding an appeal, for the screens. */
public enum AppealStatus {
    /** No admin decision yet. */
    NOT_AVAILABLE,
    /** Decided (RESOLVED) and the appeal window is open. */
    OPEN,
    /** An appeal was filed and waits for review (APPEALED). */
    FILED,
    /** The appeal was reviewed; the original decision stands. */
    UPHELD,
    /** The appeal was reviewed; the decision was changed. */
    CHANGED,
    /** No appeal was filed and the window has closed. */
    CLOSED
}

package com.gamagerecruiters.marketplace.modules.admin.domain;

/**
 * Outcome of an audited action.
 *
 * <p>The record is written as {@link #PENDING} before the action runs, then set once to
 * {@link #SUCCESS} or {@link #FAILED}. A record left {@code PENDING} means the process stopped between
 * the two steps and must be reconciled; an action is therefore never applied without an audit record.</p>
 */
public enum AuditOutcome {
    PENDING,
    SUCCESS,
    FAILED
}

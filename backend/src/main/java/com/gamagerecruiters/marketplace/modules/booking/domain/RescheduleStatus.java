package com.gamagerecruiters.marketplace.modules.booking.domain;

/** Outcome of a reschedule request (FR-BOOK-007). Only PENDING can change. */
public enum RescheduleStatus {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN,
    EXPIRED
}

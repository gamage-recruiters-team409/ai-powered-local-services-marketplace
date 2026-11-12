package com.gamagerecruiters.marketplace.modules.booking.domain;

import java.time.Instant;

/**
 * One entry in a booking's status timeline (FR-BOOK-006).
 * {@code changedBy} is the acting user ID, or {@link #SYSTEM} for automatic changes (for example expiry).
 */
public class BookingStatusChange {

    public static final String SYSTEM = "SYSTEM";

    private BookingStatus fromStatus;
    private BookingStatus toStatus;
    private Instant changedAt;
    private String changedBy;
    private String reason;

    public BookingStatusChange() {
    }

    public BookingStatusChange(BookingStatus fromStatus, BookingStatus toStatus, Instant changedAt,
                               String changedBy, String reason) {
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedAt = changedAt;
        this.changedBy = changedBy;
        this.reason = reason;
    }

    public BookingStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(BookingStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public BookingStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(BookingStatus toStatus) {
        this.toStatus = toStatus;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(Instant changedAt) {
        this.changedAt = changedAt;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

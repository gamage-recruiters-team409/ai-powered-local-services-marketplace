package com.gamagerecruiters.marketplace.modules.booking.domain;

import java.time.Instant;

/**
 * One reschedule request on a booking (FR-BOOK-007): either party proposes a new time, the other approves or
 * rejects. The booking's own slot changes only when the request is APPROVED; the history is kept on the booking.
 */
public class RescheduleRequest {

    private String id;
    /** User ID of the party who asked. */
    private String requestedBy;
    /** CUSTOMER or PROVIDER. */
    private String requestedByRole;
    private Instant previousSlotStart;
    private Instant previousSlotEnd;
    private Instant proposedSlotStart;
    private Instant proposedSlotEnd;
    private String note;
    private RescheduleStatus status;
    private Instant requestedAt;
    /** The other party must answer before this: 12 h after the request or the current start, whichever is first. */
    private Instant expiresAt;
    private Instant respondedAt;
    private String respondedBy;

    public RescheduleRequest() {
    }

    public boolean isPending() {
        return status == RescheduleStatus.PENDING;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getRequestedByRole() {
        return requestedByRole;
    }

    public void setRequestedByRole(String requestedByRole) {
        this.requestedByRole = requestedByRole;
    }

    public Instant getPreviousSlotStart() {
        return previousSlotStart;
    }

    public void setPreviousSlotStart(Instant previousSlotStart) {
        this.previousSlotStart = previousSlotStart;
    }

    public Instant getPreviousSlotEnd() {
        return previousSlotEnd;
    }

    public void setPreviousSlotEnd(Instant previousSlotEnd) {
        this.previousSlotEnd = previousSlotEnd;
    }

    public Instant getProposedSlotStart() {
        return proposedSlotStart;
    }

    public void setProposedSlotStart(Instant proposedSlotStart) {
        this.proposedSlotStart = proposedSlotStart;
    }

    public Instant getProposedSlotEnd() {
        return proposedSlotEnd;
    }

    public void setProposedSlotEnd(Instant proposedSlotEnd) {
        this.proposedSlotEnd = proposedSlotEnd;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public RescheduleStatus getStatus() {
        return status;
    }

    public void setStatus(RescheduleStatus status) {
        this.status = status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant respondedAt) {
        this.respondedAt = respondedAt;
    }

    public String getRespondedBy() {
        return respondedBy;
    }

    public void setRespondedBy(String respondedBy) {
        this.respondedBy = respondedBy;
    }
}

package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Instant;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * One status change, embedded in the dispute. Every status change appends exactly one entry.
 *
 * <p>{@code fromStatus} is null for the entry that creates the dispute. {@code reason} is optional for
 * party actions (raise, respond) and will be mandatory for admin handling actions (next PR).</p>
 */
public class StatusHistoryEntry {

    private DisputeStatus fromStatus;
    private DisputeStatus toStatus;
    private String actorId;
    private Role actorRole;
    private String reason;
    private Instant timestamp;

    public StatusHistoryEntry() {
    }

    public StatusHistoryEntry(DisputeStatus fromStatus, DisputeStatus toStatus, String actorId, Role actorRole,
                              String reason, Instant timestamp) {
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.reason = reason;
        this.timestamp = timestamp;
    }

    public DisputeStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(DisputeStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public DisputeStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(DisputeStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public Role getActorRole() {
        return actorRole;
    }

    public void setActorRole(Role actorRole) {
        this.actorRole = actorRole;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}

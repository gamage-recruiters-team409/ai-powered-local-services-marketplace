package com.gamagerecruiters.marketplace.modules.review.domain;

import java.time.Instant;
import java.util.List;

/**
 * One record in {@link Review#getModerationHistory()}: who did what, when and why.
 * Entries are only ever appended ($push), never changed.
 */
public class ModerationEntry {

    public static final String SYSTEM = "SYSTEM";

    private ModerationAction action;
    private ReviewStatus fromStatus;
    private ReviewStatus toStatus;
    private List<ModerationFlag> flags;
    private RemovalReason reasonCode;
    private String note;
    private String actorId;
    private Instant at;

    public ModerationEntry() {
    }

    public static ModerationEntry of(ModerationAction action, ReviewStatus fromStatus, ReviewStatus toStatus,
                                     String actorId, Instant at) {
        ModerationEntry entry = new ModerationEntry();
        entry.setAction(action);
        entry.setFromStatus(fromStatus);
        entry.setToStatus(toStatus);
        entry.setActorId(actorId);
        entry.setAt(at);
        return entry;
    }

    public ModerationAction getAction() {
        return action;
    }

    public void setAction(ModerationAction action) {
        this.action = action;
    }

    public ReviewStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(ReviewStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public ReviewStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(ReviewStatus toStatus) {
        this.toStatus = toStatus;
    }

    public List<ModerationFlag> getFlags() {
        return flags;
    }

    public void setFlags(List<ModerationFlag> flags) {
        this.flags = flags;
    }

    public RemovalReason getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(RemovalReason reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }
}

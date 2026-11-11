package com.gamagerecruiters.marketplace.modules.dispute.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The other party's response to a dispute, embedded in the dispute. */
public class PartyResponse {

    private String responderId;
    private String text;
    private List<String> evidence = new ArrayList<>();
    private Instant respondedAt;

    public String getResponderId() {
        return responderId;
    }

    public void setResponderId(String responderId) {
        this.responderId = responderId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence == null ? new ArrayList<>() : new ArrayList<>(evidence);
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant respondedAt) {
        this.respondedAt = respondedAt;
    }
}

package com.gamagerecruiters.marketplace.modules.review.domain;

import java.time.Instant;

/**
 * The provider's single public reply to a review (FR-REV-005).
 */
public class ProviderReply {

    private String text;
    private Instant repliedAt;

    public ProviderReply() {
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Instant getRepliedAt() {
        return repliedAt;
    }

    public void setRepliedAt(Instant repliedAt) {
        this.repliedAt = repliedAt;
    }
}

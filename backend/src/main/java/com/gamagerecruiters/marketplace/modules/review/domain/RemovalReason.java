package com.gamagerecruiters.marketplace.modules.review.domain;

/**
 * Reason a moderator gives when removing a review. The label is shown to the customer (S09)
 * and stored on the review as its status reason.
 */
public enum RemovalReason {
    CONTACT_DETAILS("Contains contact details"),
    OFFENSIVE("Offensive or abusive language"),
    SPAM("Spam or advertising"),
    FAKE("Not a genuine review of this booking"),
    NOT_RELATED("Not about this service"),
    OTHER("Breaks the review guidelines");

    private final String label;

    RemovalReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

package com.gamagerecruiters.marketplace.modules.dispute.domain;

/** The reviewing admin's verdict on an appeal. Either way the dispute becomes RESOLVED_FINAL. */
public enum AppealVerdict {
    /** The original decision stands. */
    UPHOLD,
    /** The original decision is replaced by the reviewer's decision. */
    CHANGE
}

package com.gamagerecruiters.marketplace.modules.completion.domain;

/**
 * How a completed service was confirmed.
 * CUSTOMER - the customer confirmed completion.
 * AUTO     - confirmed automatically after the 48-hour window (FR-COMP-002).
 */
public enum CompletionConfirmationType {
    CUSTOMER,
    AUTO
}

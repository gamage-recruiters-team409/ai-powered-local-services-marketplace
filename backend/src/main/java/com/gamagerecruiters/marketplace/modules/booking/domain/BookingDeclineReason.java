package com.gamagerecruiters.marketplace.modules.booking.domain;

/**
 * Mandatory dropdown reason when a provider declines a booking request (C-09).
 * Free text is only allowed (and required) for {@link #OTHER}.
 */
public enum BookingDeclineReason {
    NOT_AVAILABLE("Not available at this time"),
    OUTSIDE_SERVICE_AREA("Outside my service area"),
    JOB_NOT_SUITABLE("This job is not suitable for my service"),
    TOO_SHORT_NOTICE("Not enough notice to prepare"),
    OTHER("Other");

    private final String label;

    BookingDeclineReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

package com.gamagerecruiters.marketplace.modules.notification.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The label text is written by the platform. Values may only be added and must never be renamed.
 */
public enum NotificationReason {
    PROVIDER_UNAVAILABLE("The provider is unavailable at that time."),
    CUSTOMER_REQUEST("Cancelled at the customer's request."),
    PROVIDER_REQUEST("Cancelled at the provider's request."),
    SCHEDULE_CONFLICT("There is a schedule conflict."),
    OUTSIDE_SERVICE_AREA("The location is outside the service area."),
    PAYMENT_ISSUE("There was a payment issue."),
    NO_RESPONSE("The request was not answered in time."),
    POLICY_VIOLATION("The request did not meet the platform policy."),
    OTHER("See the booking for details.");

    private final String label;

    NotificationReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<NotificationReason> fromCode(String code) {
        return Arrays.stream(values()).filter(reason -> reason.name().equals(code)).findFirst();
    }
}

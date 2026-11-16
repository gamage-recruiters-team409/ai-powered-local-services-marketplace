package com.gamagerecruiters.marketplace.modules.booking.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Booking lifecycle statuses (approved WP05 list) and the allowed transitions between them,
 * based on the SRS booking state model (section 5.2).
 */
public enum BookingStatus {
    PENDING_PROVIDER,
    AWAITING_PAYMENT,
    CONFIRMED,
    IN_PROGRESS,
    COMPLETION_PENDING,
    COMPLETED,
    DECLINED,
    EXPIRED,
    CANCELLED_BY_CUSTOMER,
    CANCELLED_BY_PROVIDER,
    DISPUTED,
    REFUNDED,
    PARTIALLY_REFUNDED;

    /**
     * Bookings that still need action or are not settled yet.
     * Used by other modules, for example to block customer account deactivation (FR-CUST-006).
     */
    public static final Set<BookingStatus> OPEN_STATUSES = Collections.unmodifiableSet(EnumSet.of(
            PENDING_PROVIDER,
            AWAITING_PAYMENT,
            CONFIRMED,
            IN_PROGRESS,
            COMPLETION_PENDING,
            DISPUTED));

    private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(BookingStatus.class);

    static {
        // Provider accepts / declines, request expires, customer withdraws the request.
        ALLOWED_TRANSITIONS.put(PENDING_PROVIDER,
                EnumSet.of(AWAITING_PAYMENT, DECLINED, EXPIRED, CANCELLED_BY_CUSTOMER));
        // Payment authorised, payment timeout, customer cancels before paying.
        ALLOWED_TRANSITIONS.put(AWAITING_PAYMENT,
                EnumSet.of(CONFIRMED, EXPIRED, CANCELLED_BY_CUSTOMER));
        // Service started, cancelled by either party, dispute opened.
        ALLOWED_TRANSITIONS.put(CONFIRMED,
                EnumSet.of(IN_PROGRESS, CANCELLED_BY_CUSTOMER, CANCELLED_BY_PROVIDER, DISPUTED));
        // Marked complete, cancelled by either party, dispute opened.
        ALLOWED_TRANSITIONS.put(IN_PROGRESS,
                EnumSet.of(COMPLETION_PENDING, CANCELLED_BY_CUSTOMER, CANCELLED_BY_PROVIDER, DISPUTED));
        // Customer confirms or 48 h auto-confirm, dispute opened.
        ALLOWED_TRANSITIONS.put(COMPLETION_PENDING, EnumSet.of(COMPLETED, DISPUTED));
        // Dispute within the dispute window after completion (BR-008).
        ALLOWED_TRANSITIONS.put(COMPLETED, EnumSet.of(DISPUTED));
        // Admin resolves the dispute.
        ALLOWED_TRANSITIONS.put(DISPUTED, EnumSet.of(COMPLETED, REFUNDED, PARTIALLY_REFUNDED));
        // Final statuses: no further transitions.
        for (BookingStatus status : values()) {
            ALLOWED_TRANSITIONS.putIfAbsent(status, EnumSet.noneOf(BookingStatus.class));
        }
    }

    public boolean isOpen() {
        return OPEN_STATUSES.contains(this);
    }

    public boolean isFinal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    public boolean canTransitionTo(BookingStatus target) {
        return target != null && ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public Set<BookingStatus> allowedNextStatuses() {
        return Collections.unmodifiableSet(ALLOWED_TRANSITIONS.get(this));
    }
}

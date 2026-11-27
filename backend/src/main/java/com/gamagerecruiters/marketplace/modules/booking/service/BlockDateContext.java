package com.gamagerecruiters.marketplace.modules.booking.service;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Lets the block-date flow tell {@link BookingBlockedDateCheck} which confirmed bookings the provider has already
 * decided on (kept or new time requested), for the duration of one call on the current thread only.
 */
final class BlockDateContext {

    private static final ThreadLocal<Set<String>> HANDLED = new ThreadLocal<>();

    private BlockDateContext() {
    }

    static <T> T runWithHandled(Set<String> handledBookingIds, Supplier<T> action) {
        Set<String> previous = HANDLED.get();
        HANDLED.set(Set.copyOf(handledBookingIds));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                HANDLED.remove();
            } else {
                HANDLED.set(previous);
            }
        }
    }

    static Set<String> handled() {
        Set<String> handled = HANDLED.get();
        return handled == null ? Set.of() : handled;
    }
}

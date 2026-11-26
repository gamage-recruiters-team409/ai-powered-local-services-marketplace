package com.gamagerecruiters.marketplace.modules.operations.port;

import java.time.Instant;
import java.util.List;

/**
 * Overdue bookings for the operations screens (WP09): CONFIRMED / IN_PROGRESS bookings whose slot already ended and
 * the provider never marked done. Backed by the WP05 booking stats facade.
 */
public interface OverdueBookingsPort {

    /** A booking past its slot end. Display fields (ref, service title) come from the booking module; no names. */
    record OverdueBooking(String bookingId, String ref, String serviceTitle, String customerId, String providerId,
                          Instant slotStart, Instant slotEnd) {
    }

    /**
     * {@code available = false} (with a {@code reason} naming the missing source) carries NO bookings and NO numbers.
     */
    record Result(boolean available, String reason, List<OverdueBooking> bookings) {
        public static Result available(List<OverdueBooking> bookings) {
            return new Result(true, null, List.copyOf(bookings));
        }

        public static Result unavailable(String reason) {
            return new Result(false, reason, List.of());
        }
    }

    /** At most {@code limit} overdue bookings, oldest slot first. */
    Result findOverdue(int limit);
}

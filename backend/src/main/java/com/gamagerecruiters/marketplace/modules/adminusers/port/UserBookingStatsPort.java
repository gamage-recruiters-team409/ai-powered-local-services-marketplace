package com.gamagerecruiters.marketplace.modules.adminusers.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * Booking statistics of one customer or provider (WP05, booking module). BookingFacade has no statistics methods
 * yet, so only a stub exists: every method answers empty ("not available").
 */
public interface UserBookingStatsPort {

    record BookingTotals(long total, long completed, long cancelled, long disputed) {
    }

    /** {@code month} is "yyyy-MM" (UTC); {@code start} its first instant. */
    record MonthCount(String month, Instant start, long bookings) {
    }

    record BookingOutcomes(long completed, long cancelledByCustomer, long cancelledByProvider, long disputed,
                           long otherClosed) {
    }

    record Cancellations(long total, long last30Days, long cancelledByThisUser) {
    }

    record QueuedBooking(String bookingId, String status, Instant scheduledStart, String counterpartyId) {
    }

    Optional<BookingTotals> totals(String userId, Role role);

    /** One count per month in {@code months} (each given as its UTC start), in the same order. */
    Optional<List<MonthCount>> bookingsPerMonth(String userId, Role role, List<Instant> monthStarts);

    Optional<BookingOutcomes> outcomes(String userId, Role role);

    Optional<Cancellations> cancellations(String userId, Role role);

    /** Upcoming / queued bookings, soonest first. */
    Optional<List<QueuedBooking>> queued(String userId, Role role, int limit);
}

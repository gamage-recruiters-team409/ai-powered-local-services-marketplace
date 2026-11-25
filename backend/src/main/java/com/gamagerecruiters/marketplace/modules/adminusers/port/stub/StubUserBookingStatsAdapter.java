package com.gamagerecruiters.marketplace.modules.adminusers.port.stub;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.adminusers.port.UserBookingStatsPort;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;

/**
 * STUB until the booking module (WP05) exposes per-user statistics on BookingFacade. Always "not available": the
 * API shows these sections with available=false and no numbers. Replace with a BookingFacade adapter once the
 * methods exist.
 */
@Component
public class StubUserBookingStatsAdapter implements UserBookingStatsPort {

    @Override
    public Optional<BookingTotals> totals(String userId, Role role) {
        return Optional.empty();
    }

    @Override
    public Optional<List<MonthCount>> bookingsPerMonth(String userId, Role role, List<Instant> monthStarts) {
        return Optional.empty();
    }

    @Override
    public Optional<BookingOutcomes> outcomes(String userId, Role role) {
        return Optional.empty();
    }

    @Override
    public Optional<Cancellations> cancellations(String userId, Role role) {
        return Optional.empty();
    }

    @Override
    public Optional<List<QueuedBooking>> queued(String userId, Role role, int limit) {
        return Optional.empty();
    }
}

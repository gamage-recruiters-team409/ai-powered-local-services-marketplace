package com.gamagerecruiters.marketplace.modules.operations.port.adapter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.booking.dto.stats.BookingSummary;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingStatsFacade;
import com.gamagerecruiters.marketplace.modules.operations.port.OverdueBookingsPort;

/** Real overdue-bookings source backed by the WP05 {@link BookingStatsFacade#findOverdueBookings(int)}. */
@Component
public class BookingStatsOverdueAdapter implements OverdueBookingsPort {

    private static final Logger log = LoggerFactory.getLogger(BookingStatsOverdueAdapter.class);

    private final BookingStatsFacade bookingStats;

    public BookingStatsOverdueAdapter(BookingStatsFacade bookingStats) {
        this.bookingStats = bookingStats;
    }

    @Override
    public Result findOverdue(int limit) {
        try {
            List<OverdueBooking> rows = bookingStats.findOverdueBookings(limit).stream()
                    .map(BookingStatsOverdueAdapter::toRow)
                    .toList();
            return Result.available(rows);
        } catch (RuntimeException e) {
            log.warn("event=OPS_OVERDUE_SOURCE_FAILED cause={}", e.getClass().getSimpleName());
            return Result.unavailable("Booking overdue source failed for this request (WP05 "
                    + "BookingStatsFacade.findOverdueBookings).");
        }
    }

    private static OverdueBooking toRow(BookingSummary s) {
        return new OverdueBooking(s.bookingId(), s.ref(), s.serviceTitle(), s.customerId(), s.providerId(),
                s.slotStart(), s.slotEnd());
    }
}

package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.availability.service.BlockedDateBookingCheck;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

/**
 * Stops a date from being blocked while it still has bookings nobody decided about. The plain
 * POST /availability/me/blocked-dates therefore only works for an empty day; a day with bookings goes through
 * POST /bookings/blocked-dates (decide first, then block).
 */
@Component
public class BookingBlockedDateCheck implements BlockedDateBookingCheck {

    /** Bookings that still hold a future time on that day. */
    static final Set<BookingStatus> AFFECTED_STATUSES = EnumSet.of(BookingStatus.PENDING_PROVIDER,
            BookingStatus.AWAITING_PAYMENT, BookingStatus.CONFIRMED);

    private final BookingRepository bookingRepository;

    public BookingBlockedDateCheck(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Override
    public void assertNoBookingsToHandle(String providerId, LocalDate date) {
        Set<String> handled = BlockDateContext.handled();
        long open = bookingsOnDate(bookingRepository, providerId, date).stream()
                .filter(b -> !handled.contains(b.getId()))
                .count();
        if (open > 0) {
            throw new ResourceConflictException("BOOKINGS_ON_DATE",
                    "This date has " + open + " booking(s). Please choose what to do with them before blocking it.");
        }
    }

    /** Affected bookings that start on this date (Asia/Colombo), earliest first. */
    static List<Booking> bookingsOnDate(BookingRepository repository, String providerId, LocalDate date) {
        Instant dayStart = date.atStartOfDay(SlotServiceImpl.BUSINESS_ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(SlotServiceImpl.BUSINESS_ZONE).toInstant();
        return repository.findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(
                        providerId, AFFECTED_STATUSES, dayEnd, dayStart).stream()
                .filter(b -> b.getSlotStart() != null && !b.getSlotStart().isBefore(dayStart))
                .sorted((a, b) -> a.getSlotStart().compareTo(b.getSlotStart()))
                .toList();
    }
}

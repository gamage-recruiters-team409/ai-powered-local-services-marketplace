package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.availability.dto.ProviderSchedule;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursResponse;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityFacade;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.AvailableSlotsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.TimeSlot;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

/**
 * Builds bookable slots from: the provider's weekly hours for that day, blocked dates,
 * the 3-hour minimum notice (C-01), and existing bookings plus the provider's buffer (C-03).
 */
@Service
public class SlotServiceImpl implements SlotService {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Colombo");

    /** Slots start every 30 minutes inside working hours. */
    static final int SLOT_STEP_MINUTES = 30;

    /** C-01: a booking must start at least 3 hours from now. */
    static final Duration MIN_NOTICE = Duration.ofHours(3);

    /** How far ahead customers can look for slots. */
    static final int MAX_DAYS_AHEAD = 365;

    static final int MIN_DURATION_MINUTES = 15;
    static final int MAX_DURATION_MINUTES = 480;

    /** Bookings that hold the provider's time. Declined, expired, cancelled and finished ones do not. */
    public static final Set<BookingStatus> TIME_BLOCKING_STATUSES = EnumSet.of(
            BookingStatus.PENDING_PROVIDER,
            BookingStatus.AWAITING_PAYMENT,
            BookingStatus.CONFIRMED,
            BookingStatus.IN_PROGRESS);

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final AvailabilityFacade availabilityFacade;
    private final BookingRepository bookingRepository;
    private final Clock clock;

    @Autowired
    public SlotServiceImpl(AvailabilityFacade availabilityFacade, BookingRepository bookingRepository) {
        this(availabilityFacade, bookingRepository, Clock.system(BUSINESS_ZONE));
    }

    SlotServiceImpl(AvailabilityFacade availabilityFacade, BookingRepository bookingRepository, Clock clock) {
        this.availabilityFacade = availabilityFacade;
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    @Override
    public AvailableSlotsResponse getAvailableSlots(String providerId, String dateText, String durationText) {
        if (providerId == null || providerId.isBlank()) {
            throw badRequest("INVALID_PROVIDER", "providerId is required");
        }
        LocalDate date = parseDate(dateText);
        int duration = parseDuration(durationText);

        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw badRequest("INVALID_DATE", "date must be between today and " + MAX_DAYS_AHEAD + " days ahead");
        }

        ProviderSchedule schedule = availabilityFacade.getSchedule(providerId);
        int buffer = schedule.bufferMinutes();

        if (availabilityFacade.isDateBlocked(providerId, date)) {
            return response(providerId, date, duration, buffer, "BLOCKED", List.of());
        }

        WeeklyHoursResponse hours = schedule.weeklyHours().stream()
                .filter(h -> h.dayOfWeek() == date.getDayOfWeek())
                .findFirst()
                .orElse(null);
        if (hours == null || !hours.enabled() || hours.startTime() == null || hours.endTime() == null) {
            return response(providerId, date, duration, buffer, "DAY_OFF", List.of());
        }

        Instant workStart = date.atTime(LocalTime.parse(hours.startTime())).atZone(BUSINESS_ZONE).toInstant();
        Instant workEnd = date.atTime(LocalTime.parse(hours.endTime())).atZone(BUSINESS_ZONE).toInstant();
        Instant earliestStart = Instant.now(clock).plus(MIN_NOTICE);
        Duration bufferDuration = Duration.ofMinutes(buffer);

        // Bookings that could touch this day once the buffer is added on both sides.
        List<Booking> existing = bookingRepository
                .findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(
                        providerId, TIME_BLOCKING_STATUSES, workEnd.plus(bufferDuration),
                        workStart.minus(bufferDuration));

        List<TimeSlot> slots = new ArrayList<>();
        Duration length = Duration.ofMinutes(duration);
        for (Instant start = workStart; !start.plus(length).isAfter(workEnd);
             start = start.plus(Duration.ofMinutes(SLOT_STEP_MINUTES))) {
            Instant end = start.plus(length);
            if (start.isBefore(earliestStart) || overlapsExisting(start, end, existing, bufferDuration)) {
                continue;
            }
            slots.add(new TimeSlot(start, end, local(start), local(end)));
        }

        return response(providerId, date, duration, buffer, "WORKING", slots);
    }

    /** A slot conflicts if it is closer than the buffer to any existing booking. */
    private boolean overlapsExisting(Instant start, Instant end, List<Booking> existing, Duration buffer) {
        for (Booking booking : existing) {
            if (booking.getSlotStart() == null || booking.getSlotEnd() == null) {
                continue;
            }
            Instant blockedFrom = booking.getSlotStart().minus(buffer);
            Instant blockedTo = booking.getSlotEnd().plus(buffer);
            if (start.isBefore(blockedTo) && end.isAfter(blockedFrom)) {
                return true;
            }
        }
        return false;
    }

    private AvailableSlotsResponse response(String providerId, LocalDate date, int duration, int buffer,
                                            String dayStatus, List<TimeSlot> slots) {
        return new AvailableSlotsResponse(providerId, date.toString(), BUSINESS_ZONE.getId(), duration, buffer,
                dayStatus, slots);
    }

    private String local(Instant instant) {
        return HH_MM.format(instant.atZone(BUSINESS_ZONE));
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("INVALID_DATE", "date is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw badRequest("INVALID_DATE", "date must be a valid date in yyyy-MM-dd format");
        }
    }

    private int parseDuration(String value) {
        int minutes;
        try {
            minutes = Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw badRequest("INVALID_DURATION", "durationMinutes is required and must be a number");
        }
        if (minutes < MIN_DURATION_MINUTES || minutes > MAX_DURATION_MINUTES || minutes % 15 != 0) {
            throw badRequest("INVALID_DURATION", "durationMinutes must be between " + MIN_DURATION_MINUTES
                    + " and " + MAX_DURATION_MINUTES + " in steps of 15");
        }
        return minutes;
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}

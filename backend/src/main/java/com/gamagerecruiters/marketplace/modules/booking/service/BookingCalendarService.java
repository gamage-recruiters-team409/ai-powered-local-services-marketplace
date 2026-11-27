package com.gamagerecruiters.marketplace.modules.booking.service;

import com.gamagerecruiters.marketplace.modules.booking.dto.CalendarResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.CalendarSubscriptionResponse;

/** Provider calendar view (FR-SCHD-005) and .ics export / private subscription link (FR-SCHD-006). */
public interface BookingCalendarService {

    /** Provider's days and bookings from {@code from} to {@code to} (yyyy-MM-dd, at most 42 days). */
    CalendarResponse calendar(String from, String to);

    /** One-time .ics file of the logged-in customer's or provider's bookings. */
    String exportIcs();

    CalendarSubscriptionResponse getSubscription();

    /** Creates the private link, or replaces it (the old link stops working). The URL is shown only now. */
    CalendarSubscriptionResponse createOrResetSubscription();

    void revokeSubscription();

    /** Public feed for a calendar app; 404 for an unknown or reset link. */
    String feed(String token);
}

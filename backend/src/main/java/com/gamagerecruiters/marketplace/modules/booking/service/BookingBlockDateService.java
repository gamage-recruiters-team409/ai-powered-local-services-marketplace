package com.gamagerecruiters.marketplace.modules.booking.service;

import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateImpactResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateResult;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateWithBookingsRequest;

/** Block a date that already has bookings (Block a date screen). For the logged-in provider. */
public interface BookingBlockDateService {

    /** Bookings on that date and what will happen to them. */
    BlockDateImpactResponse impact(String date);

    /** Applies one decision per confirmed booking, declines pending requests, then blocks the date. */
    BlockDateResult blockWithBookings(BlockDateWithBookingsRequest request);
}

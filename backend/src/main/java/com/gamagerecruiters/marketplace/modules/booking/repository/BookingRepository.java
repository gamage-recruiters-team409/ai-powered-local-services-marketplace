package com.gamagerecruiters.marketplace.modules.booking.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/**
 * Internal to the booking module. Other modules must use {@code BookingFacade}.
 */
public interface BookingRepository extends MongoRepository<Booking, String> {

    boolean existsByCustomerIdAndStatusIn(String customerId, Collection<BookingStatus> statuses);

    /**
     * Provider bookings in the given statuses whose time overlaps [from, to):
     * slotStart &lt; to AND slotEnd &gt; from. Uses the providerId_1_slotStart_1 index.
     */
    List<Booking> findByProviderIdAndStatusInAndSlotStartLessThanAndSlotEndGreaterThan(
            String providerId, Collection<BookingStatus> statuses, Instant to, Instant from);
}

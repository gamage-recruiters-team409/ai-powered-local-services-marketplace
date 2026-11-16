package com.gamagerecruiters.marketplace.modules.booking.repository;

import java.util.Collection;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;

/**
 * Internal to the booking module. Other modules must use {@code BookingFacade}.
 */
public interface BookingRepository extends MongoRepository<Booking, String> {

    boolean existsByCustomerIdAndStatusIn(String customerId, Collection<BookingStatus> statuses);
}

package com.gamagerecruiters.marketplace.modules.booking.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

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

    /** Requests whose provider response deadline has passed (expiry job). */
    List<Booking> findTop100ByStatusAndResponseDeadlineBeforeOrderByResponseDeadlineAsc(
            BookingStatus status, Instant before);

    /** Accepted bookings whose payment due time has passed (expiry job). */
    List<Booking> findTop100ByStatusAndPaymentDueAtBeforeOrderByPaymentDueAtAsc(BookingStatus status, Instant before);

    /** Customer's bookings (My Bookings); uses customerId_1_status_1. */
    Page<Booking> findByCustomerId(String customerId, Pageable pageable);

    Page<Booking> findByCustomerIdAndStatusIn(String customerId, Collection<BookingStatus> statuses, Pageable pageable);

    /** Provider's bookings; uses providerId_1_slotStart_1. */
    Page<Booking> findByProviderId(String providerId, Pageable pageable);

    Page<Booking> findByProviderIdAndStatusIn(String providerId, Collection<BookingStatus> statuses, Pageable pageable);

    /** Bookings with a PENDING reschedule request whose answer time has passed (oldest first via paging). */
    @Query("{ 'rescheduleRequests': { '$elemMatch': { 'status': 'PENDING', 'expiresAt': { '$lt': ?0 } } } }")
    List<Booking> findWithExpiredRescheduleRequests(Instant now, Pageable pageable);
}

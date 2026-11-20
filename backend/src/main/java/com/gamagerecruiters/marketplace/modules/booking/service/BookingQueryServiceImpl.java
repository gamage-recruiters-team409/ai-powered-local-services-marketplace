package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingAddress;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingPriceSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingAddressRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingDetailsResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingListItem;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPageResponse;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

/**
 * Read side of bookings for their own customer and provider. Newest slot first.
 * The provider sees the customer's full address and notes only from CONFIRMED (C-08);
 * before that only the district.
 */
@Service
public class BookingQueryServiceImpl implements BookingQueryService {

    static final int MAX_PAGE_SIZE = 50;

    private static final Sort NEWEST_SLOT_FIRST = Sort.by(Sort.Direction.DESC, "slotStart");
    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(SlotServiceImpl.BUSINESS_ZONE);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(SlotServiceImpl.BUSINESS_ZONE);

    private final BookingRepository bookingRepository;

    public BookingQueryServiceImpl(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Override
    public BookingPageResponse<BookingListItem> listCustomerBookings(Set<BookingStatus> statuses, int page, int size) {
        String customerId = currentUserId();
        Pageable pageable = pageable(page, size);
        Page<Booking> bookings = statuses == null || statuses.isEmpty()
                ? bookingRepository.findByCustomerId(customerId, pageable)
                : bookingRepository.findByCustomerIdAndStatusIn(customerId, statuses, pageable);
        return BookingPageResponse.from(bookings.map(BookingQueryServiceImpl::toListItem));
    }

    @Override
    public BookingPageResponse<BookingListItem> listProviderBookings(Set<BookingStatus> statuses, int page, int size) {
        String providerId = currentUserId();
        Pageable pageable = pageable(page, size);
        Page<Booking> bookings = statuses == null || statuses.isEmpty()
                ? bookingRepository.findByProviderId(providerId, pageable)
                : bookingRepository.findByProviderIdAndStatusIn(providerId, statuses, pageable);
        return BookingPageResponse.from(bookings.map(BookingQueryServiceImpl::toListItem));
    }

    @Override
    public BookingDetailsResponse getBookingDetails(String bookingId) {
        String userId = currentUserId();
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found"));

        String viewerRole;
        if (userId.equals(booking.getCustomerId())) {
            viewerRole = "CUSTOMER";
        } else if (userId.equals(booking.getProviderId())) {
            viewerRole = "PROVIDER";
        } else {
            throw new AccessDeniedException("Access denied: you are not part of this booking");
        }

        boolean addressVisible = "CUSTOMER".equals(viewerRole) || booking.getStatus().isContactVisible();
        BookingAddress a = booking.getAddress();
        BookingAddressRequest address = a == null ? null : addressVisible
                ? new BookingAddressRequest(a.getLine1(), a.getLine2(), a.getCity(), a.getDistrict(), a.getPostalCode())
                : new BookingAddressRequest(null, null, null, a.getDistrict(), null);

        BookingPriceSnapshot p = booking.getPriceSnapshot();
        BookingDetailsResponse.Price price = p == null ? null : new BookingDetailsResponse.Price(
                p.basePrice(),
                p.addOns().stream().map(x -> new BookingDetailsResponse.AddOn(x.addOnId(), x.title(),
                        x.extraPrice(), x.extraDurationMinutes())).toList(),
                p.totalPrice(), p.currency(), p.pricingModel());

        List<BookingDetailsResponse.TimelineEntry> timeline = booking.getTimeline().stream()
                .map(c -> new BookingDetailsResponse.TimelineEntry(c.getFromStatus(), c.getToStatus(),
                        c.getChangedAt(), actorOf(booking, c), c.getReason()))
                .toList();

        return new BookingDetailsResponse(booking.getId(), booking.getRef(), booking.getStatus(), viewerRole,
                booking.getListingId(), p == null ? null : p.serviceTitle(), booking.getCustomerId(),
                booking.getProviderId(), booking.getSeriesId(), booking.getSlotStart(), booking.getSlotEnd(),
                local(LOCAL_DATE, booking), localTime(booking.getSlotStart()), localTime(booking.getSlotEnd()),
                booking.getDurationMinutes(), addressVisible, address,
                addressVisible ? booking.getNotes() : null, price, booking.getResponseDeadline(),
                booking.getAcceptedAt(), booking.getPaymentDueAt(), booking.getCreatedAt(), timeline);
    }

    private static String currentUserId() {
        String userId = CurrentUser.getUserId();
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
        return userId;
    }

    static Pageable pageable(int page, int size) {
        if (page < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "page must be 0 or more");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE",
                    "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, NEWEST_SLOT_FIRST);
    }

    /** Role of whoever made the change; user IDs of other people are not exposed. */
    private static String actorOf(Booking booking, BookingStatusChange change) {
        String by = change.getChangedBy();
        if (by == null || BookingStatusChange.SYSTEM.equals(by)) {
            return "SYSTEM";
        }
        if (by.equals(booking.getCustomerId())) {
            return "CUSTOMER";
        }
        if (by.equals(booking.getProviderId())) {
            return "PROVIDER";
        }
        return "ADMIN";
    }

    static BookingListItem toListItem(Booking booking) {
        BookingPriceSnapshot p = booking.getPriceSnapshot();
        return new BookingListItem(booking.getId(), booking.getRef(), booking.getStatus(), booking.getListingId(),
                p == null ? null : p.serviceTitle(), booking.getCustomerId(), booking.getProviderId(),
                booking.getSlotStart(), booking.getSlotEnd(), local(LOCAL_DATE, booking),
                localTime(booking.getSlotStart()), localTime(booking.getSlotEnd()),
                p == null ? null : p.totalPrice(), p == null ? null : p.currency(),
                booking.getAddress() == null ? null : booking.getAddress().getDistrict(), booking.getCreatedAt());
    }

    private static String local(DateTimeFormatter format, Booking booking) {
        return booking.getSlotStart() == null ? null : format.format(booking.getSlotStart());
    }

    private static String localTime(java.time.Instant instant) {
        return instant == null ? null : LOCAL_TIME.format(instant);
    }
}

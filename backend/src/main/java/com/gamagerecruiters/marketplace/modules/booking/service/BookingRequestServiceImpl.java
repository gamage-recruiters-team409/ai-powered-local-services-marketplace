package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingAddress;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingAddressRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.CreateBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

/**
 * Creates customer booking requests.
 *
 * <p>Steps: authenticated customer → take the provider's short booking lock → re-check the chosen
 * slot (same rules as the available-slots API: hours, blocked date, 3 h notice, existing bookings
 * plus buffer) → save as PENDING_PROVIDER → release the lock. Because the re-check and the save
 * happen inside the lock, two requests for overlapping times cannot both succeed; the second one
 * sees the first booking and gets 409 SLOT_UNAVAILABLE.
 */
@Service
public class BookingRequestServiceImpl implements BookingRequestService {

    /** C-02: provider response deadline – 12 hours after the request ... */
    static final Duration RESPONSE_WINDOW = Duration.ofHours(12);

    /** ... or 1 hour before the start, whichever is earlier. */
    static final Duration RESPONSE_CUTOFF_BEFORE_START = Duration.ofHours(1);

    static final int MAX_REFERENCE_ATTEMPTS = 3;

    /** How long a request waits for the provider's booking lock: up to 40 × 50 ms = 2 s. */
    static final int LOCK_ATTEMPTS = 40;
    static final long LOCK_RETRY_MILLIS = 50;

    private static final Logger log = LoggerFactory.getLogger(BookingRequestServiceImpl.class);
    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(SlotServiceImpl.BUSINESS_ZONE);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(SlotServiceImpl.BUSINESS_ZONE);

    private final BookingRepository bookingRepository;
    private final SlotService slotService;
    private final ProviderBookingLock providerBookingLock;
    private final BookingReferenceGenerator referenceGenerator;
    private final Clock clock;

    @Autowired
    public BookingRequestServiceImpl(BookingRepository bookingRepository, SlotService slotService,
                                     ProviderBookingLock providerBookingLock,
                                     BookingReferenceGenerator referenceGenerator) {
        this(bookingRepository, slotService, providerBookingLock, referenceGenerator, Clock.systemUTC());
    }

    BookingRequestServiceImpl(BookingRepository bookingRepository, SlotService slotService,
                              ProviderBookingLock providerBookingLock, BookingReferenceGenerator referenceGenerator,
                              Clock clock) {
        this.bookingRepository = bookingRepository;
        this.slotService = slotService;
        this.providerBookingLock = providerBookingLock;
        this.referenceGenerator = referenceGenerator;
        this.clock = clock;
    }

    @Override
    public BookingResponse createBookingRequest(CreateBookingRequest request) {
        String customerId = CurrentUser.getUserId();
        if (customerId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
        if (customerId.equals(request.providerId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_BOOKING_NOT_ALLOWED",
                    "You cannot book your own service");
        }

        String lockOwner = acquireProviderLock(request.providerId());
        Booking saved;
        try {
            if (!slotService.isSlotAvailable(request.providerId(), request.slotStart(), request.durationMinutes())) {
                throw slotUnavailable();
            }
            Instant now = Instant.now(clock);
            saved = saveWithUniqueReference(newBooking(customerId, request, now), now);
        } finally {
            providerBookingLock.release(request.providerId(), lockOwner);
        }

        log.info("Booking request created: ref={}, provider={}, slotStart={}", saved.getRef(),
                saved.getProviderId(), saved.getSlotStart());
        return toResponse(saved);
    }

    private Booking newBooking(String customerId, CreateBookingRequest request, Instant now) {
        Instant start = request.slotStart();
        Instant end = start.plus(Duration.ofMinutes(request.durationMinutes()));

        Booking booking = new Booking();
        booking.setCustomerId(customerId);
        booking.setProviderId(request.providerId());
        booking.setListingId(request.listingId());
        booking.setSlotStart(start);
        booking.setSlotEnd(end);
        booking.setDurationMinutes(request.durationMinutes());
        booking.setAddress(toAddress(request.address()));
        booking.setNotes(clean(request.notes()));
        booking.setStatus(BookingStatus.PENDING_PROVIDER);
        booking.setResponseDeadline(responseDeadline(now, start));
        booking.setCreatedAt(now);
        booking.setUpdatedAt(now);
        booking.getTimeline().add(new BookingStatusChange(null, BookingStatus.PENDING_PROVIDER, now, customerId,
                "Booking requested"));
        return booking;
    }

    /** C-02: the earlier of 12 hours after the request or 1 hour before the start. */
    static Instant responseDeadline(Instant requestedAt, Instant slotStart) {
        Instant twelveHours = requestedAt.plus(RESPONSE_WINDOW);
        Instant beforeStart = slotStart.minus(RESPONSE_CUTOFF_BEFORE_START);
        return twelveHours.isBefore(beforeStart) ? twelveHours : beforeStart;
    }

    private Booking saveWithUniqueReference(Booking booking, Instant now) {
        for (int attempt = 1; ; attempt++) {
            booking.setRef(referenceGenerator.next(now));
            try {
                return bookingRepository.insert(booking);
            } catch (DuplicateKeyException e) {
                // Only ref_1 is unique on bookings: a reference collision. Generate a new one.
                if (attempt >= MAX_REFERENCE_ATTEMPTS) {
                    throw new IllegalStateException("Could not generate a unique booking reference", e);
                }
                booking.setId(null);
            }
        }
    }

    private String acquireProviderLock(String providerId) {
        for (int attempt = 1; attempt <= LOCK_ATTEMPTS; attempt++) {
            String owner = providerBookingLock.tryAcquire(providerId);
            if (owner != null) {
                return owner;
            }
            try {
                Thread.sleep(LOCK_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ResourceConflictException("BOOKING_BUSY",
                "Another booking for this provider is being processed. Please try again.");
    }

    private ResourceConflictException slotUnavailable() {
        return new ResourceConflictException("SLOT_UNAVAILABLE",
                "This time slot is no longer available. Please choose another slot.");
    }

    private BookingAddress toAddress(BookingAddressRequest address) {
        return new BookingAddress(clean(address.line1()), clean(address.line2()), clean(address.city()),
                clean(address.district()), clean(address.postalCode()));
    }

    private static String clean(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private BookingResponse toResponse(Booking booking) {
        BookingAddress a = booking.getAddress();
        BookingAddressRequest address = a == null ? null
                : new BookingAddressRequest(a.getLine1(), a.getLine2(), a.getCity(), a.getDistrict(), a.getPostalCode());
        return new BookingResponse(
                booking.getId(),
                booking.getRef(),
                booking.getStatus(),
                booking.getCustomerId(),
                booking.getProviderId(),
                booking.getListingId(),
                booking.getSlotStart(),
                booking.getSlotEnd(),
                LOCAL_DATE.format(booking.getSlotStart()),
                LOCAL_TIME.format(booking.getSlotStart()),
                LOCAL_TIME.format(booking.getSlotEnd()),
                booking.getDurationMinutes(),
                address,
                booking.getNotes(),
                booking.getResponseDeadline(),
                booking.getCreatedAt());
    }
}

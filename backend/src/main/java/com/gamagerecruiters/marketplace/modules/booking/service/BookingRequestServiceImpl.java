package com.gamagerecruiters.marketplace.modules.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingAddOnSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingAddress;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingPriceSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingAddressRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.CreateBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.gamagerecruiters.marketplace.modules.listing.dto.PriceSnapshotResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;
import com.mongodb.MongoException;

/**
 * Creates customer booking requests.
 *
 * <p>Before anything is saved: the listing must exist and be bookable (WP03
 * {@code getListingPriceSnapshot}: ACTIVE, not hidden, active category), it must belong to the requested
 * provider, and the provider must be APPROVED (WP02 {@code isApprovedProvider}). Duration and the
 * immutable price/commission snapshot come from the listing, never from the request body.
 * {@code booking.creation.enabled} (default true) remains as a kill switch (503 when false).
 *
 * <p>When enabled, the provider's guard document is created if missing, then each request runs in one MongoDB transaction: claim the provider guard →
 * re-check the slot (same rules as the available-slots API) → insert the PENDING_PROVIDER
 * booking. Concurrent requests for the same provider conflict on the guard document; MongoDB
 * aborts all but one, and the others retry and then see the committed booking
 * (409 SLOT_UNAVAILABLE). No lease is involved, so a slow request cannot lose its protection.
 */
@Service
public class BookingRequestServiceImpl implements BookingRequestService {

    /** C-02: provider response deadline – 12 hours after the request ... */
    static final Duration RESPONSE_WINDOW = Duration.ofHours(12);

    /** ... or 1 hour before the start, whichever is earlier. */
    static final Duration RESPONSE_CUTOFF_BEFORE_START = Duration.ofHours(1);

    static final int MAX_REFERENCE_ATTEMPTS = 3;

    /** Retries after a transaction write conflict: up to 50 attempts, 100 ms apart (about 5 s). */
    static final int MAX_TRANSACTION_ATTEMPTS = 50;
    static final long DEFAULT_RETRY_DELAY_MILLIS = 100;

    private static final Logger log = LoggerFactory.getLogger(BookingRequestServiceImpl.class);
    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(SlotServiceImpl.BUSINESS_ZONE);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(SlotServiceImpl.BUSINESS_ZONE);

    private final BookingRepository bookingRepository;
    private final ListingService listingService;
    private final ProviderVerificationFacade providerVerification;
    private final SlotService slotService;
    private final ProviderBookingGuard providerBookingGuard;
    private final BookingReferenceGenerator referenceGenerator;
    private final TransactionOperations transactions;
    private final boolean creationEnabled;
    private final Clock clock;
    private final long retryDelayMillis;

    @Autowired
    public BookingRequestServiceImpl(BookingRepository bookingRepository, ListingService listingService,
                                     ProviderVerificationFacade providerVerification, SlotService slotService,
                                     ProviderBookingGuard providerBookingGuard,
                                     BookingReferenceGenerator referenceGenerator,
                                     ObjectProvider<MongoTransactionManager> transactionManager,
                                     @Value("${booking.creation.enabled:true}") boolean creationEnabled) {
        this(bookingRepository, listingService, providerVerification, slotService, providerBookingGuard,
                referenceGenerator,
                transactionTemplate(transactionManager.getIfAvailable()), creationEnabled, Clock.systemUTC(),
                DEFAULT_RETRY_DELAY_MILLIS);
    }

    BookingRequestServiceImpl(BookingRepository bookingRepository, ListingService listingService,
                              ProviderVerificationFacade providerVerification, SlotService slotService,
                              ProviderBookingGuard providerBookingGuard, BookingReferenceGenerator referenceGenerator,
                              TransactionOperations transactions, boolean creationEnabled, Clock clock,
                              long retryDelayMillis) {
        this.bookingRepository = bookingRepository;
        this.listingService = listingService;
        this.providerVerification = providerVerification;
        this.slotService = slotService;
        this.providerBookingGuard = providerBookingGuard;
        this.referenceGenerator = referenceGenerator;
        this.transactions = transactions;
        this.creationEnabled = creationEnabled;
        this.clock = clock;
        this.retryDelayMillis = retryDelayMillis;
    }

    private static TransactionOperations transactionTemplate(MongoTransactionManager manager) {
        return manager == null ? null : new TransactionTemplate(manager);
    }

    @Override
    public BookingResponse createBookingRequest(CreateBookingRequest request) {
        String customerId = CurrentUser.getUserId();
        if (customerId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }
        if (!creationEnabled || transactions == null) {
            // Kill switch, and fail closed when MongoDB transactions are not available.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_CREATION_UNAVAILABLE",
                    "Booking requests are not available yet. Please try again later.");
        }
        if (customerId.equals(request.providerId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_BOOKING_NOT_ALLOWED",
                    "You cannot book your own service");
        }

        // WP03: 404 when missing, 409 LISTING_NOT_BOOKABLE when not ACTIVE / hidden / inactive category.
        PriceSnapshotResponse listing = listingService.getListingPriceSnapshot(request.listingId(),
                request.addOnIds());
        if (listing.providerId() == null || !listing.providerId().equals(request.providerId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LISTING_PROVIDER_MISMATCH",
                    "This service does not belong to the selected provider");
        }
        // WP02 / BR-002: only APPROVED providers can receive bookings.
        if (!providerVerification.isApprovedProvider(listing.providerId())) {
            throw new ResourceConflictException("PROVIDER_NOT_BOOKABLE",
                    "This provider is not accepting bookings right now");
        }
        if (listing.totalDurationMin() <= 0 || listing.totalPrice() == null) {
            throw new ResourceConflictException("LISTING_NOT_BOOKABLE",
                    "This service cannot be booked right now");
        }
        // Same duration rule as GET /available-slots: rounded up to 15 minutes, max one working day.
        if (!BookingDurationPolicy.isSchedulable(listing.totalDurationMin())) {
            throw new ResourceConflictException("DURATION_NOT_SCHEDULABLE",
                    "This service with the selected add-ons takes longer than "
                            + BookingDurationPolicy.MAX_SCHEDULED_MINUTES / 60
                            + " hours and cannot be booked as one visit");
        }

        Booking saved = createInTransactionWithRetry(customerId, request, listing);

        log.info("Booking request created: ref={}, provider={}, slotStart={}", saved.getRef(),
                saved.getProviderId(), saved.getSlotStart());
        return toResponse(saved);
    }

    private Booking createInTransactionWithRetry(String customerId, CreateBookingRequest request,
                                                 PriceSnapshotResponse listing) {
        int referenceCollisions = 0;
        providerBookingGuard.ensureExists(request.providerId(), Instant.now(clock));
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            Instant now = Instant.now(clock);
            Booking booking = newBooking(customerId, request, listing, now);
            booking.setRef(referenceGenerator.next(now));
            try {
                return transactions.execute(status -> {
                    providerBookingGuard.claim(request.providerId(), now);
                    if (!slotService.isSlotAvailable(request.providerId(), request.slotStart(),
                            BookingDurationPolicy.toScheduledMinutes(listing.totalDurationMin()))) {
                        throw slotUnavailable();
                    }
                    return bookingRepository.insert(booking);
                });
            } catch (RuntimeException e) {
                if (isTransientTransactionError(e)) {
                    // Another booking for this provider is committing right now: wait and retry.
                    sleepBeforeRetry();
                } else if (e instanceof DuplicateKeyException) {
                    // Only ref_1 is unique on bookings: a reference collision. Retry with a new reference.
                    if (++referenceCollisions >= MAX_REFERENCE_ATTEMPTS) {
                        throw new IllegalStateException("Could not generate a unique booking reference", e);
                    }
                } else {
                    throw e;
                }
            }
        }
        throw new ResourceConflictException("BOOKING_BUSY",
                "Another booking for this provider is being processed. Please try again.");
    }

    static boolean isTransientTransactionError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof MongoException mongo
                    && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private void sleepBeforeRetry() {
        if (retryDelayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(retryDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceConflictException("BOOKING_BUSY",
                    "Another booking for this provider is being processed. Please try again.");
        }
    }

    private Booking newBooking(String customerId, CreateBookingRequest request, PriceSnapshotResponse listing,
                               Instant now) {
        // Calendar time on the 15-minute grid; the real service minutes stay in the price snapshot.
        int duration = BookingDurationPolicy.toScheduledMinutes(listing.totalDurationMin());
        Instant start = request.slotStart();
        Instant end = start.plus(Duration.ofMinutes(duration));

        Booking booking = new Booking();
        booking.setCustomerId(customerId);
        booking.setProviderId(request.providerId());
        booking.setListingId(request.listingId());
        booking.setSlotStart(start);
        booking.setSlotEnd(end);
        booking.setDurationMinutes(duration);
        booking.setPriceSnapshot(toPriceSnapshot(listing, now));
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

    static BookingPriceSnapshot toPriceSnapshot(PriceSnapshotResponse listing, Instant now) {
        List<BookingAddOnSnapshot> addOns = listing.selectedAddOns().stream()
                .map(a -> new BookingAddOnSnapshot(a.addOnId(), a.title(), a.extraPrice(),
                        a.extraDurationMin() == null ? 0 : a.extraDurationMin()))
                .toList();
        return new BookingPriceSnapshot(
                listing.title(),
                listing.categoryId(),
                listing.pricingModel() == null ? null : listing.pricingModel().name(),
                listing.basePrice(),
                addOns,
                listing.totalPrice(),
                BookingPriceSnapshot.PLATFORM_CURRENCY,
                listing.commissionRate(),
                listing.totalDurationMin(),
                listing.capturedAt() == null ? now : listing.capturedAt());
    }

    /** C-02: the earlier of 12 hours after the request or 1 hour before the start. */
    static Instant responseDeadline(Instant requestedAt, Instant slotStart) {
        Instant twelveHours = requestedAt.plus(RESPONSE_WINDOW);
        Instant beforeStart = slotStart.minus(RESPONSE_CUTOFF_BEFORE_START);
        return twelveHours.isBefore(beforeStart) ? twelveHours : beforeStart;
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
                booking.getCreatedAt(),
                booking.getPriceSnapshot() == null ? null : booking.getPriceSnapshot().serviceTitle(),
                booking.getPriceSnapshot() == null ? null : booking.getPriceSnapshot().totalPrice(),
                booking.getPriceSnapshot() == null ? null : booking.getPriceSnapshot().currency());
    }
}

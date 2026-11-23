package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionOperations;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingAddressRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.CreateBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.dto.AddOnItemResponse;
import com.gamagerecruiters.marketplace.modules.listing.dto.PriceSnapshotResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;
import com.mongodb.MongoException;

@ExtendWith(MockitoExtension.class)
class BookingRequestServiceImplTest {

    // Friday 2026-11-20 08:00 Colombo
    private static final Instant NOW = Instant.parse("2026-11-20T02:30:00Z");
    // Monday 2026-11-23 09:00–10:00 Colombo
    private static final Instant SLOT_START = Instant.parse("2026-11-23T03:30:00Z");
    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private ListingService listingService;

    @Mock
    private ProviderVerificationFacade providerVerification;

    @Mock
    private SlotService slotService;

    @Mock
    private ProviderBookingGuard providerBookingGuard;

    @Mock
    private BookingReferenceGenerator referenceGenerator;

    private BookingRequestServiceImpl enabledService;

    @BeforeEach
    void setUp() {
        enabledService = service(true, TransactionOperations.withoutTransaction());
    }

    private BookingRequestServiceImpl service(boolean enabled, TransactionOperations transactions) {
        return new BookingRequestServiceImpl(bookingRepository, listingService, providerVerification, slotService,
                providerBookingGuard, referenceGenerator, transactions, enabled, Clock.fixed(NOW, ZoneOffset.UTC), 0);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static CreateBookingRequest request(String providerId) {
        return new CreateBookingRequest(providerId, "listing-1", SLOT_START, List.of(),
                new BookingAddressRequest("  12 Temple Road ", null, "Nugegoda", "Colombo", "10250"),
                "  Gate code 1234 ");
    }

    /** Listing-1: 60 minutes, LKR 5000, 10% commission, owned by PROVIDER. */
    private static PriceSnapshotResponse listing(String ownerId, List<AddOnItemResponse> addOns, BigDecimal total,
                                                 int totalMinutes) {
        return new PriceSnapshotResponse("listing-1", ownerId, "cat-1", "Home Cleaning", PricingModel.FIXED,
                new BigDecimal("5000.00"), 60, new BigDecimal("0.10"), addOns, total, totalMinutes,
                Instant.parse("2026-11-20T02:29:59Z"));
    }

    private void givenBookableListing() {
        when(listingService.getListingPriceSnapshot("listing-1", List.of()))
                .thenReturn(listing(PROVIDER, List.of(), new BigDecimal("5000.00"), 60));
        when(providerVerification.isApprovedProvider(PROVIDER)).thenReturn(true);
    }

    private static UncategorizedMongoDbException writeConflict() {
        MongoException conflict = new MongoException(112, "WriteConflict");
        conflict.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        return new UncategorizedMongoDbException("Write conflict during transaction", conflict);
    }

    private void givenInsertAssignsId(String id) {
        when(bookingRepository.insert(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(id);
            return b;
        });
    }

    @Test
    @DisplayName("Kill switch: creation disabled → 503 BOOKING_CREATION_UNAVAILABLE, nothing touched")
    void creationDisabledByDefault() {
        loginAs(CUSTOMER, Role.CUSTOMER);

        assertThatThrownBy(() -> service(false, TransactionOperations.withoutTransaction())
                .createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((ApiException) e).getErrorCode()).isEqualTo("BOOKING_CREATION_UNAVAILABLE");
                });
        verifyNoInteractions(providerBookingGuard, slotService, bookingRepository);
    }

    @Test
    @DisplayName("Gate: no MongoDB transaction manager available → 503 (fail closed)")
    void noTransactionManagerFailsClosed() {
        loginAs(CUSTOMER, Role.CUSTOMER);

        assertThatThrownBy(() -> service(true, null).createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("BOOKING_CREATION_UNAVAILABLE"));
        verifyNoInteractions(providerBookingGuard, slotService, bookingRepository);
    }

    @Test
    @DisplayName("Enabled: creates PENDING_PROVIDER booking; guard claimed before slot re-check")
    void createsBooking() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenBookableListing();
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(true);
        when(referenceGenerator.next(NOW)).thenReturn("BK-20261120-ABCDEF");
        givenInsertAssignsId("booking-1");

        BookingResponse response = enabledService.createBookingRequest(request(PROVIDER));

        assertThat(response.id()).isEqualTo("booking-1");
        assertThat(response.ref()).isEqualTo("BK-20261120-ABCDEF");
        assertThat(response.status()).isEqualTo(BookingStatus.PENDING_PROVIDER);
        assertThat(response.customerId()).isEqualTo(CUSTOMER);
        assertThat(response.slotEnd()).isEqualTo(Instant.parse("2026-11-23T04:30:00Z"));
        assertThat(response.localStart()).isEqualTo("09:00");
        assertThat(response.responseDeadline()).isEqualTo(Instant.parse("2026-11-20T14:30:00Z"));
        assertThat(response.address().line1()).isEqualTo("12 Temple Road");
        assertThat(response.notes()).isEqualTo("Gate code 1234");
        assertThat(response.durationMinutes()).isEqualTo(60);
        assertThat(response.serviceTitle()).isEqualTo("Home Cleaning");
        assertThat(response.totalPrice()).isEqualByComparingTo("5000.00");
        assertThat(response.currency()).isEqualTo("LKR");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(providerBookingGuard, slotService, bookingRepository);
        order.verify(providerBookingGuard).ensureExists(PROVIDER, NOW);
        order.verify(providerBookingGuard).claim(PROVIDER, NOW);
        order.verify(slotService).isSlotAvailable(PROVIDER, SLOT_START, 60);
        order.verify(bookingRepository).insert(argThat((Booking b) ->
                b.getTimeline().size() == 1 && CUSTOMER.equals(b.getTimeline().get(0).getChangedBy())));
    }

    @Test
    @DisplayName("Response deadline (C-02) is 1 h before the start when that is earlier than 12 h")
    void responseDeadlineBeforeStart() {
        assertThat(BookingRequestServiceImpl.responseDeadline(Instant.parse("2026-11-20T02:30:00Z"),
                Instant.parse("2026-11-20T07:30:00Z"))).isEqualTo(Instant.parse("2026-11-20T06:30:00Z"));
    }

    @Test
    @DisplayName("Not logged in: 401 UNAUTHORIZED")
    void unauthenticated() {
        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(providerBookingGuard, slotService, bookingRepository);
    }

    @Test
    @DisplayName("Booking yourself: 400 SELF_BOOKING_NOT_ALLOWED")
    void selfBooking() {
        loginAs(PROVIDER, Role.CUSTOMER);

        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("SELF_BOOKING_NOT_ALLOWED"));
        verifyNoInteractions(providerBookingGuard, bookingRepository);
    }

    @Test
    @DisplayName("Slot no longer available: 409 SLOT_UNAVAILABLE, nothing saved")
    void slotUnavailable() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenBookableListing();
        when(referenceGenerator.next(NOW)).thenReturn("BK-1");
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(false);

        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("SLOT_UNAVAILABLE"));
        verify(bookingRepository, never()).insert(any(Booking.class));
    }

    @Test
    @DisplayName("Transaction write conflict: retried, then succeeds")
    void writeConflictIsRetried() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenBookableListing();
        when(referenceGenerator.next(NOW)).thenReturn("BK-1", "BK-2");
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(true);
        org.mockito.Mockito.doThrow(writeConflict()).doNothing().when(providerBookingGuard).claim(PROVIDER, NOW);
        givenInsertAssignsId("booking-2");

        BookingResponse response = enabledService.createBookingRequest(request(PROVIDER));

        assertThat(response.id()).isEqualTo("booking-2");
        verify(providerBookingGuard, times(2)).claim(PROVIDER, NOW);
        verify(bookingRepository, times(1)).insert(any(Booking.class));
    }

    @Test
    @DisplayName("Write conflict on every attempt: 409 BOOKING_BUSY after the retry budget, nothing saved")
    void persistentConflictReturnsBusy() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenBookableListing();
        when(referenceGenerator.next(NOW)).thenReturn("BK-1");
        org.mockito.Mockito.doThrow(writeConflict()).when(providerBookingGuard).claim(eq(PROVIDER), any(Instant.class));

        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("BOOKING_BUSY"));
        verify(providerBookingGuard, times(BookingRequestServiceImpl.MAX_TRANSACTION_ATTEMPTS)).claim(PROVIDER, NOW);
        verify(bookingRepository, never()).insert(any(Booking.class));
    }

    @Test
    @DisplayName("Reference collision: retried with a new reference")
    void referenceCollisionRetries() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        givenBookableListing();
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 60)).thenReturn(true);
        when(referenceGenerator.next(NOW)).thenReturn("BK-20261120-AAAAAA", "BK-20261120-BBBBBB");
        when(bookingRepository.insert(any(Booking.class)))
                .thenThrow(new DuplicateKeyException("E11000 ref_1"))
                .thenAnswer(inv -> {
                    Booking b = inv.getArgument(0);
                    b.setId("booking-3");
                    return b;
                });

        assertThat(enabledService.createBookingRequest(request(PROVIDER)).ref()).isEqualTo("BK-20261120-BBBBBB");
    }

    @Test
    @DisplayName("Transient error detection follows the cause chain")
    void transientDetection() {
        assertThat(BookingRequestServiceImpl.isTransientTransactionError(writeConflict())).isTrue();
        assertThat(BookingRequestServiceImpl.isTransientTransactionError(new RuntimeException("other"))).isFalse();
    }

    // ---- WP02 / WP03 eligibility and snapshots ----

    @Test
    @DisplayName("Price snapshot (price, commission, add-ons, duration) is captured from the listing, not the request")
    void snapshotCapturedFromListing() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        AddOnItemResponse addOn = new AddOnItemResponse("ao-1", "Deep clean", new BigDecimal("1500.00"), 30, true);
        CreateBookingRequest withAddOn = new CreateBookingRequest(PROVIDER, "listing-1", SLOT_START, List.of("ao-1"),
                new BookingAddressRequest("12 Temple Road", null, "Nugegoda", "Colombo", "10250"), null);
        when(listingService.getListingPriceSnapshot("listing-1", List.of("ao-1")))
                .thenReturn(listing(PROVIDER, List.of(addOn), new BigDecimal("6500.00"), 90));
        when(providerVerification.isApprovedProvider(PROVIDER)).thenReturn(true);
        when(referenceGenerator.next(NOW)).thenReturn("BK-1");
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 90)).thenReturn(true);
        givenInsertAssignsId("booking-9");

        BookingResponse response = enabledService.createBookingRequest(withAddOn);

        assertThat(response.durationMinutes()).isEqualTo(90);
        assertThat(response.slotEnd()).isEqualTo(SLOT_START.plusSeconds(90 * 60));
        verify(bookingRepository).insert(argThat((Booking b) -> {
            var snap = b.getPriceSnapshot();
            return snap != null
                    && snap.totalPrice().compareTo(new BigDecimal("6500.00")) == 0
                    && snap.basePrice().compareTo(new BigDecimal("5000.00")) == 0
                    && snap.commissionRate().compareTo(new BigDecimal("0.10")) == 0
                    && "LKR".equals(snap.currency())
                    && "FIXED".equals(snap.pricingModel())
                    && snap.durationMinutes() == 90
                    && snap.addOns().size() == 1
                    && "ao-1".equals(snap.addOns().get(0).addOnId())
                    && snap.capturedAt().equals(Instant.parse("2026-11-20T02:29:59Z"));
        }));
    }

    @Test
    @DisplayName("Listing not found (WP03 404) → propagated, nothing saved")
    void listingNotFound() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(listingService.getListingPriceSnapshot("listing-1", List.of()))
                .thenThrow(new ResourceNotFoundException("LISTING_NOT_FOUND", "Listing not found"));

        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(providerVerification, providerBookingGuard, slotService, bookingRepository);
    }

    @Test
    @DisplayName("Listing not bookable (WP03 409 LISTING_NOT_BOOKABLE) → propagated, nothing saved")
    void listingNotBookable() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(listingService.getListingPriceSnapshot("listing-1", List.of()))
                .thenThrow(new ResourceConflictException("LISTING_NOT_BOOKABLE", "not bookable"));

        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("LISTING_NOT_BOOKABLE"));
        verifyNoInteractions(providerVerification, providerBookingGuard, slotService, bookingRepository);
    }

    @Test
    @DisplayName("Listing owned by another provider → 400 LISTING_PROVIDER_MISMATCH, nothing saved")
    void listingProviderMismatch() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(listingService.getListingPriceSnapshot("listing-1", List.of()))
                .thenReturn(listing("other-provider", List.of(), new BigDecimal("5000.00"), 60));

        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((ApiException) e).getErrorCode()).isEqualTo("LISTING_PROVIDER_MISMATCH");
                });
        verifyNoInteractions(providerVerification, providerBookingGuard, slotService, bookingRepository);
    }

    @Test
    @DisplayName("Provider not APPROVED (WP02) → 409 PROVIDER_NOT_BOOKABLE, nothing saved")
    void providerNotApproved() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(listingService.getListingPriceSnapshot("listing-1", List.of()))
                .thenReturn(listing(PROVIDER, List.of(), new BigDecimal("5000.00"), 60));
        when(providerVerification.isApprovedProvider(PROVIDER)).thenReturn(false);

        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("PROVIDER_NOT_BOOKABLE"));
        verifyNoInteractions(providerBookingGuard, slotService, bookingRepository);
    }

    @Test
    @DisplayName("WP03 70-minute service (60 + 10 add-on): slot checked and blocked for 75 minutes, snapshot keeps 70")
    void nonAlignedDurationRoundedUp() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(listingService.getListingPriceSnapshot("listing-1", List.of()))
                .thenReturn(listing(PROVIDER, List.of(), new BigDecimal("5500.00"), 70));
        when(providerVerification.isApprovedProvider(PROVIDER)).thenReturn(true);
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 75)).thenReturn(true);
        when(referenceGenerator.next(NOW)).thenReturn("BK-20261120-ABCDEF");
        givenInsertAssignsId("booking-1");

        BookingResponse response = enabledService.createBookingRequest(request(PROVIDER));

        assertThat(response.durationMinutes()).isEqualTo(75);
        assertThat(response.slotEnd()).isEqualTo(SLOT_START.plusSeconds(75 * 60));
        verify(slotService).isSlotAvailable(PROVIDER, SLOT_START, 75);
        verify(bookingRepository).insert(argThat((Booking b) -> b.getPriceSnapshot().durationMinutes() == 70));
    }

    @Test
    @DisplayName("Exactly 480 minutes is bookable")
    void maxDurationBookable() {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(listingService.getListingPriceSnapshot("listing-1", List.of()))
                .thenReturn(listing(PROVIDER, List.of(), new BigDecimal("5000.00"), 480));
        when(providerVerification.isApprovedProvider(PROVIDER)).thenReturn(true);
        when(slotService.isSlotAvailable(PROVIDER, SLOT_START, 480)).thenReturn(true);
        when(referenceGenerator.next(NOW)).thenReturn("BK-20261120-ABCDEF");
        givenInsertAssignsId("booking-1");

        assertThat(enabledService.createBookingRequest(request(PROVIDER)).durationMinutes()).isEqualTo(480);
    }

    @ParameterizedTest(name = "{0} minutes → 409 DURATION_NOT_SCHEDULABLE")
    @ValueSource(ints = {481, 600, 1440})
    @DisplayName("WP03 allows longer services than one booking: rejected before any slot check or write")
    void overLimitDurationRejected(int totalMinutes) {
        loginAs(CUSTOMER, Role.CUSTOMER);
        when(listingService.getListingPriceSnapshot("listing-1", List.of()))
                .thenReturn(listing(PROVIDER, List.of(), new BigDecimal("5000.00"), totalMinutes));
        when(providerVerification.isApprovedProvider(PROVIDER)).thenReturn(true);

        assertThatThrownBy(() -> enabledService.createBookingRequest(request(PROVIDER)))
                .isInstanceOf(ResourceConflictException.class)
                .satisfies(e -> assertThat(((ResourceConflictException) e).getErrorCode())
                        .isEqualTo("DURATION_NOT_SCHEDULABLE"));
        verifyNoInteractions(providerBookingGuard, slotService, bookingRepository);
    }
}

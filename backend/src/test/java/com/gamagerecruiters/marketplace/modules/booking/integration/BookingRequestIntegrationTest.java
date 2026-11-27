package com.gamagerecruiters.marketplace.modules.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockReason;
import com.gamagerecruiters.marketplace.modules.availability.dto.CreateBlockedDateRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.UpdateAvailabilityRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursRequest;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityService;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateWithBookingsRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingAddressRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.CreateBookingRequest;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityFacade;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingBlockDateService;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingRequestService;
import com.gamagerecruiters.marketplace.modules.listing.domain.PricingModel;
import com.gamagerecruiters.marketplace.modules.listing.dto.PriceSnapshotResponse;
import com.gamagerecruiters.marketplace.modules.listing.service.ListingService;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;
import com.gamagerecruiters.marketplace.modules.booking.service.SlotService;
import com.gamagerecruiters.marketplace.modules.booking.service.SlotServiceImpl;

/**
 * Booking request against a real MongoDB (Testcontainers): stored booking, slot re-check and
 * double-booking protection under simultaneous requests (MongoDB transaction + provider guard).
 * WP03 listing snapshot and WP02 provider approval are mocked (their own modules test them); the
 * booking, snapshot and transaction behaviour run against real MongoDB.
 * Requires Docker. Skip explicitly with -DSKIP_DOCKER_TESTS=true.
 */
@SpringBootTest(properties = "booking.creation.enabled=true")
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class BookingRequestIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BookingRequestIntegrationTest.class);

    private static final String PROVIDER = "provider-r1";

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl();
        });
    }

    @BeforeAll
    static void startContainer() {
        if (!mongoDBContainer.isRunning()) {
            mongoDBContainer.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private BookingRequestService bookingRequestService;

    @Autowired
    private AvailabilityService availabilityService;

    @Autowired
    private MongoTemplate mongoTemplate;

    /** WP03 listing contract: listing-1 (60 min, LKR 5000, 10% commission) owned by PROVIDER. */
    @MockitoBean
    private ListingService listingService;

    /** WP02 provider approval: PROVIDER is APPROVED. */
    @MockitoBean
    private ProviderVerificationFacade providerVerification;

    /** Real SlotService; only the regression test below slows one call down. */
    @MockitoSpyBean
    private SlotService slotService;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new Query(), Booking.class);
        mongoTemplate.remove(Query.query(Criteria.where("providerId").is(PROVIDER)), "blocked_dates");
        // Provider works every day 08:00–20:00 Colombo, 30 minutes buffer.
        List<WeeklyHoursRequest> week = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            week.add(new WeeklyHoursRequest(day, true, "08:00", "20:00"));
        }
        availabilityService.updateAvailability(PROVIDER, new UpdateAvailabilityRequest(week, 30));

        when(listingService.getListingPriceSnapshot(anyString(), anyList())).thenReturn(new PriceSnapshotResponse(
                "listing-1", PROVIDER, "cat-1", "Home Cleaning", PricingModel.FIXED, new BigDecimal("5000.00"), 60,
                new BigDecimal("0.10"), List.of(), new BigDecimal("5000.00"), 60, Instant.now()));
        when(providerVerification.isApprovedProvider(PROVIDER)).thenReturn(true);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String userId) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", Role.CUSTOMER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** {@code hh:mm} Colombo time, 7 days from today, as a UTC instant. */
    private static Instant nextWeekAt(String localTime) {
        LocalDate date = LocalDate.now(SlotServiceImpl.BUSINESS_ZONE).plusDays(7);
        return date.atTime(LocalTime.parse(localTime)).atZone(SlotServiceImpl.BUSINESS_ZONE).toInstant();
    }

    private static CreateBookingRequest request(Instant slotStart) {
        return new CreateBookingRequest(PROVIDER, "listing-1", slotStart, List.of(),
                new BookingAddressRequest("12 Temple Road", null, "Nugegoda", "Colombo", "10250"), null);
    }

    private long storedOpenBookings() {
        return mongoTemplate.count(Query.query(Criteria.where("providerId").is(PROVIDER)
                .and("status").in(BookingStatus.OPEN_STATUSES)), Booking.class);
    }

    @Test
    @DisplayName("Real MongoDB: booking request is stored as PENDING_PROVIDER with reference, deadline and address")
    void createsAndStoresBooking() {
        loginAs("customer-r1");

        BookingResponse response = bookingRequestService.createBookingRequest(request(nextWeekAt("10:00")));

        Booking stored = mongoTemplate.findById(response.id(), Booking.class);
        log.info("Booking request stored: ref={}, status={}, district={}, responseDeadline={}",
                stored.getRef(), stored.getStatus(), stored.getAddress().getDistrict(), stored.getResponseDeadline());

        assertThat(stored.getStatus()).isEqualTo(BookingStatus.PENDING_PROVIDER);
        assertThat(stored.getRef()).matches("^BK-\\d{8}-[A-Z2-9]{6}$");
        assertThat(stored.getCustomerId()).isEqualTo("customer-r1");
        assertThat(stored.getAddress().getDistrict()).isEqualTo("Colombo");
        assertThat(stored.getResponseDeadline()).isNotNull();
        assertThat(stored.getTimeline()).hasSize(1);
        // Price snapshot survives the MongoDB round trip unchanged.
        assertThat(stored.getDurationMinutes()).isEqualTo(60);
        assertThat(stored.getPriceSnapshot()).isNotNull();
        assertThat(stored.getPriceSnapshot().totalPrice()).isEqualByComparingTo("5000.00");
        assertThat(stored.getPriceSnapshot().commissionRate()).isEqualByComparingTo("0.10");
        assertThat(stored.getPriceSnapshot().currency()).isEqualTo("LKR");
        assertThat(stored.getPriceSnapshot().serviceTitle()).isEqualTo("Home Cleaning");
    }

    @Test
    @DisplayName("Real MongoDB: the same slot cannot be booked twice (409 SLOT_UNAVAILABLE)")
    void sameSlotTwiceRejected() {
        loginAs("customer-r1");
        bookingRequestService.createBookingRequest(request(nextWeekAt("11:00")));

        loginAs("customer-r2");
        assertThatThrownBy(() -> bookingRequestService.createBookingRequest(request(nextWeekAt("11:00"))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("SLOT_UNAVAILABLE"));
        assertThat(storedOpenBookings()).isEqualTo(1);
    }

    @Test
    @DisplayName("Real MongoDB: 10 simultaneous requests for overlapping slots → exactly 1 booking stored")
    void simultaneousOverlappingRequestsCreateOneBooking() throws Exception {
        int parallel = 10;
        ExecutorService executor = Executors.newFixedThreadPool(parallel);
        CountDownLatch ready = new CountDownLatch(parallel);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();

        try {
            for (int i = 0; i < parallel; i++) {
                String customer = "customer-c" + i;
                // Half ask for 14:00, half for 14:30 – all overlap each other.
                Instant slot = nextWeekAt(i % 2 == 0 ? "14:00" : "14:30");
                results.add(executor.submit(() -> {
                    loginAs(customer);
                    ready.countDown();
                    start.await();
                    try {
                        bookingRequestService.createBookingRequest(request(slot));
                        return "CREATED";
                    } catch (ApiException e) {
                        return e.getErrorCode();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(60, TimeUnit.SECONDS));
            }

            log.info("Simultaneous booking requests: requests={}, created={}, rejected={}, stored open bookings={}",
                    parallel, outcomes.stream().filter("CREATED"::equals).count(),
                    outcomes.stream().filter(o -> !"CREATED".equals(o)).count(), storedOpenBookings());

            assertThat(outcomes).filteredOn("CREATED"::equals).hasSize(1);
            assertThat(outcomes).filteredOn(o -> !"CREATED".equals(o))
                    .allMatch(o -> o.equals("SLOT_UNAVAILABLE") || o.equals("BOOKING_BUSY"));
            assertThat(storedOpenBookings()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
    @Test
    @DisplayName("Real MongoDB: first request held 12 s (longer than the old 10 s lease) – overlapping request still cannot double book")
    void slowRequestBeyondOldLeaseStillProtected() throws Exception {
        Instant first = nextWeekAt("16:00");
        Instant overlapping = nextWeekAt("16:30");
        long holdMillis = 12_000;

        CountDownLatch firstInsideTransaction = new CountDownLatch(1);
        AtomicBoolean delayed = new AtomicBoolean(false);
        doAnswer(invocation -> {
            // Only the first request's slot check is slowed down; it has already claimed the guard.
            if (delayed.compareAndSet(false, true)) {
                firstInsideTransaction.countDown();
                Thread.sleep(holdMillis);
            }
            return invocation.callRealMethod();
        }).when(slotService).isSlotAvailable(anyString(), any(Instant.class), anyInt());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> slow = executor.submit(() -> attempt("customer-slow", first));
            assertThat(firstInsideTransaction.await(10, TimeUnit.SECONDS)).isTrue();

            // Wait past the old 10 s lease while the first request is still inside its transaction.
            Thread.sleep(10_500);
            Future<String> second = executor.submit(() -> attempt("customer-second", overlapping));

            String slowOutcome = slow.get(60, TimeUnit.SECONDS);
            String secondOutcome = second.get(60, TimeUnit.SECONDS);

            log.info("Beyond-lease regression: hold={}ms, slowRequest={}, overlappingRequest={}, stored open bookings={}",
                    holdMillis, slowOutcome, secondOutcome, storedOpenBookings());

            assertThat(List.of(slowOutcome, secondOutcome)).filteredOn("CREATED"::equals).hasSize(1);
            assertThat(List.of(slowOutcome, secondOutcome))
                    .allMatch(o -> o.equals("CREATED") || o.equals("SLOT_UNAVAILABLE") || o.equals("BOOKING_BUSY"));
            assertThat(storedOpenBookings()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // ---------- PR #65 F2: blocking a date is serialised with booking creation (provider guard) ----------

    @Autowired
    private BookingBlockDateService blockDateService;

    @Autowired
    private AvailabilityFacade availabilityFacade;

    private static void loginAsProvider() {
        UserPrincipal principal = new UserPrincipal(PROVIDER, PROVIDER + "@test.local", Role.PROVIDER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private long openBookingsOn(LocalDate date) {
        Instant dayStart = date.atStartOfDay(SlotServiceImpl.BUSINESS_ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(SlotServiceImpl.BUSINESS_ZONE).toInstant();
        return mongoTemplate.count(Query.query(Criteria.where("providerId").is(PROVIDER)
                .and("status").in(BookingStatus.OPEN_STATUSES)
                .and("slotStart").gte(dayStart).lt(dayEnd)), Booking.class);
    }

    @Test
    @DisplayName("Real MongoDB: booking requests and date blocking at the same moment on the same date → never "
            + "a blocked date that still has an open booking")
    void bookingAndBlockingSameDateAtOnce() throws Exception {
        int dates = 8;
        ExecutorService executor = Executors.newFixedThreadPool(dates * 2);
        CountDownLatch ready = new CountDownLatch(dates * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<LocalDate> days = new ArrayList<>();
        List<Future<String>> bookingResults = new ArrayList<>();
        List<Future<String>> blockResults = new ArrayList<>();
        try {
            for (int i = 0; i < dates; i++) {
                LocalDate day = LocalDate.now(SlotServiceImpl.BUSINESS_ZONE).plusDays(20 + i);
                days.add(day);
                Instant ten = day.atTime(10, 0).atZone(SlotServiceImpl.BUSINESS_ZONE).toInstant();
                String customer = "customer-f2-" + i;
                // Even dates: the plain availability POST (empty-day path). Odd dates: the booking-aware flow.
                boolean plainPost = i % 2 == 0;
                bookingResults.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return attempt(customer, ten);
                }));
                blockResults.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    loginAsProvider();
                    try {
                        if (plainPost) {
                            availabilityService.createBlockedDate(PROVIDER,
                                    new CreateBlockedDateRequest(day.toString(), BlockReason.PERSONAL, null));
                        } else {
                            blockDateService.blockWithBookings(new BlockDateWithBookingsRequest(day.toString(),
                                    BlockReason.PERSONAL, null, List.of()));
                        }
                        return "BLOCKED";
                    } catch (ApiException e) {
                        return e.getErrorCode();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (int i = 0; i < dates; i++) {
                String booking = bookingResults.get(i).get(60, TimeUnit.SECONDS);
                String block = blockResults.get(i).get(60, TimeUnit.SECONDS);
                LocalDate day = days.get(i);
                boolean blocked = availabilityFacade.isDateBlocked(PROVIDER, day);
                long open = openBookingsOn(day);
                log.info("Block vs booking on {}: booking={}, block={}, blocked={}, open bookings={}",
                        day, booking, block, blocked, open);

                // The invariant the guard protects.
                assertThat(blocked && open > 0).as("blocked date %s with an open booking", day).isFalse();
                assertThat(booking).isIn("CREATED", "SLOT_UNAVAILABLE", "BOOKING_BUSY");
                assertThat(block).isIn("BLOCKED", "BOOKINGS_ON_DATE", "BOOKING_BUSY");
                assertThat(blocked).isEqualTo("BLOCKED".equals(block));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private String attempt(String customer, Instant slot) {
        loginAs(customer);
        try {
            bookingRequestService.createBookingRequest(request(slot));
            return "CREATED";
        } catch (ApiException e) {
            return e.getErrorCode();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

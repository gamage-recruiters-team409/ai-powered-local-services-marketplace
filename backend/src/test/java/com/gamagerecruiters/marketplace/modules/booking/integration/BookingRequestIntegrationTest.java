package com.gamagerecruiters.marketplace.modules.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.availability.dto.UpdateAvailabilityRequest;
import com.gamagerecruiters.marketplace.modules.availability.dto.WeeklyHoursRequest;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityService;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingAddressRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingResponse;
import com.gamagerecruiters.marketplace.modules.booking.dto.CreateBookingRequest;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingRequestService;
import com.gamagerecruiters.marketplace.modules.booking.service.SlotServiceImpl;

/**
 * Booking request against a real MongoDB (Testcontainers): stored booking, slot re-check and
 * double-booking protection under simultaneous requests.
 * Requires Docker. Skip explicitly with -DSKIP_DOCKER_TESTS=true.
 */
@SpringBootTest
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

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new Query(), Booking.class);
        // Provider works every day 08:00–20:00 Colombo, 30 minutes buffer.
        List<WeeklyHoursRequest> week = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            week.add(new WeeklyHoursRequest(day, true, "08:00", "20:00"));
        }
        availabilityService.updateAvailability(PROVIDER, new UpdateAvailabilityRequest(week, 30));
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
        return new CreateBookingRequest(PROVIDER, "listing-1", slotStart, 60,
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
}

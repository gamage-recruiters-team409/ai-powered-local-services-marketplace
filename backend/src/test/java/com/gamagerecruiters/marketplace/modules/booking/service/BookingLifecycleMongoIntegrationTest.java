package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.availability.domain.BlockReason;
import com.gamagerecruiters.marketplace.modules.availability.dto.CreateBlockedDateRequest;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityFacade;
import com.gamagerecruiters.marketplace.modules.availability.service.AvailabilityService;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingPriceSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleRequest;
import com.gamagerecruiters.marketplace.modules.booking.domain.RescheduleStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateAction;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateDecision;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateResult;
import com.gamagerecruiters.marketplace.modules.booking.dto.BlockDateWithBookingsRequest;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingListItem;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPageResponse;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

/**
 * PR #51 review (F3, F5) against a real MongoDB (Testcontainers):
 * deterministic My Bookings pagination with identical slotStart values, and the one WP05/WP07
 * payment-timeout contract shared by BookingExpiryJob and markPaymentExpired.
 * Requires Docker. Skip with -DSKIP_DOCKER_TESTS=true.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class BookingLifecycleMongoIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");
    private static final Instant SAME_SLOT = Instant.parse("2026-12-01T04:30:00Z");
    private static final String PAY_REF = "pi_3TestPayment01";

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
    private MongoTemplate mongoTemplate;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private BookingQueryService queryService;

    private BookingPaymentPolicy policy;
    private BookingFacadeImpl facade;
    private BookingExpiryJob expiryJob;

    @BeforeEach
    void setUp() {
        // Spring's test listener opens a mock HTTP request; remove it so trusted calls run like a background job.
        RequestContextHolder.resetRequestAttributes();
        mongoTemplate.remove(new Query(), Booking.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        policy = new BookingPaymentPolicy(15, 15);
        BookingNotifier notifier = Mockito.mock(BookingNotifier.class);
        facade = new BookingFacadeImpl(bookingRepository, policy, notifier, clock);
        expiryJob = new BookingExpiryJob(bookingRepository, policy, notifier, clock, true);
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void loginAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Booking save(String customerId, String providerId, BookingStatus status, Instant slotStart) {
        Booking b = new Booking();
        b.setRef("BK-IT-" + UUID.randomUUID());
        b.setCustomerId(customerId);
        b.setProviderId(providerId);
        b.setListingId("lst-1");
        b.setSlotStart(slotStart);
        b.setSlotEnd(slotStart.plus(Duration.ofHours(1)));
        b.setDurationMinutes(60);
        b.setStatus(status);
        b.setCreatedAt(NOW);
        b.setUpdatedAt(NOW);
        b.setPriceSnapshot(new BookingPriceSnapshot("Home Cleaning", "cat-1", "FIXED", new BigDecimal("5000.00"),
                List.of(), new BigDecimal("5000.00"), "LKR", new BigDecimal("0.10"), 60, NOW));
        return mongoTemplate.insert(b);
    }

    /** Reads every page of size 3 and returns the booking IDs in the order the API returned them. */
    private List<String> readAllPages(boolean asCustomer, Set<BookingStatus> statuses) {
        List<String> ids = new ArrayList<>();
        int page = 0;
        BookingPageResponse<BookingListItem> result;
        do {
            result = asCustomer
                    ? queryService.listCustomerBookings(statuses, page, 3)
                    : queryService.listProviderBookings(statuses, page, 3);
            result.content().forEach(item -> ids.add(item.id()));
            page++;
        } while (page < result.totalPages());
        return ids;
    }

    // ---------- F3: deterministic pagination ----------

    @Test
    @DisplayName("F3 customer: 8 bookings at the SAME slotStart over 3 pages – each exactly once, same order every read")
    void customerPagesWithIdenticalSlotStart() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            expected.add(save("cust-1", "prov-" + i, BookingStatus.CONFIRMED, SAME_SLOT).getId());
        }
        String later = save("cust-1", "prov-x", BookingStatus.CONFIRMED, SAME_SLOT.plus(Duration.ofDays(1))).getId();
        save("cust-2", "prov-1", BookingStatus.CONFIRMED, SAME_SLOT); // another customer: never listed
        loginAs("cust-1", Role.CUSTOMER);

        List<String> first = readAllPages(true, null);
        List<String> second = readAllPages(true, null);
        List<String> third = readAllPages(true, null);

        assertThat(first).hasSize(9).doesNotHaveDuplicates();
        assertThat(first.get(0)).isEqualTo(later);
        // Ties on slotStart are ordered by _id descending.
        assertThat(first.subList(1, 9)).containsExactlyElementsOf(
                expected.stream().sorted(Comparator.reverseOrder()).toList());
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    @Test
    @DisplayName("F3 provider + status filter: same-slot bookings across pages – each exactly once, filter kept")
    void providerPagesWithIdenticalSlotStartAndStatusFilter() {
        List<String> confirmed = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            confirmed.add(save("cust-" + i, "prov-1", BookingStatus.CONFIRMED, SAME_SLOT).getId());
        }
        for (int i = 0; i < 3; i++) {
            save("cust-d" + i, "prov-1", BookingStatus.DECLINED, SAME_SLOT);
        }
        loginAs("prov-1", Role.PROVIDER);

        List<String> first = readAllPages(false, EnumSet.of(BookingStatus.CONFIRMED));
        List<String> second = readAllPages(false, EnumSet.of(BookingStatus.CONFIRMED));

        assertThat(first).hasSize(7).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(confirmed);
        assertThat(second).isEqualTo(first);
        assertThat(readAllPages(false, null)).hasSize(10).doesNotHaveDuplicates();
    }

    // ---------- F5: one payment-timeout contract ----------

    private Booking awaitingPayment(Duration dueAgo) {
        Booking b = save("cust-p", "prov-p", BookingStatus.AWAITING_PAYMENT, SAME_SLOT);
        b.setAcceptedAt(NOW.minus(dueAgo).minus(Duration.ofMinutes(15)));
        b.setPaymentDueAt(NOW.minus(dueAgo));
        return bookingRepository.save(b);
    }

    private BookingStatus statusOf(String id) {
        return bookingRepository.findById(id).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("F5 before the deadline: job does not expire, WP07 expire → 409 PAYMENT_NOT_DUE")
    void beforeDeadline() {
        Booking b = awaitingPayment(Duration.ofMinutes(-5));

        assertThat(expiryJob.run().paymentsExpired()).isZero();
        assertThatThrownBy(() -> TrustedBookingSystemCall.run(TrustedBookingSystemCall.Purpose.PAYMENT_PROCESSING,
                () -> facade.markPaymentExpired(b.getId(), PAY_REF)))
                .isInstanceOfSatisfying(ResourceConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PAYMENT_NOT_DUE"));
        assertThat(statusOf(b.getId())).isEqualTo(BookingStatus.AWAITING_PAYMENT);
    }

    @Test
    @DisplayName("F5 during grace: job and WP07 both keep it AWAITING_PAYMENT, then payment success → CONFIRMED")
    void duringGraceThenPaymentSucceeds() {
        Booking b = awaitingPayment(Duration.ofMinutes(10));

        assertThat(expiryJob.run().paymentsExpired()).isZero();
        assertThatThrownBy(() -> TrustedBookingSystemCall.run(TrustedBookingSystemCall.Purpose.PAYMENT_PROCESSING,
                () -> facade.markPaymentExpired(b.getId(), PAY_REF)))
                .isInstanceOfSatisfying(ResourceConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PAYMENT_NOT_DUE"));

        TrustedBookingSystemCall.run(TrustedBookingSystemCall.Purpose.PAYMENT_PROCESSING,
                () -> facade.markPaymentConfirmed(b.getId(), PAY_REF));

        Booking stored = bookingRepository.findById(b.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(stored.getTimeline()).extracting(BookingStatusChange::getReference).contains(PAY_REF);
    }

    @Test
    @DisplayName("F5 after grace: the job expires it; WP07 expire is then idempotent; a late payment → 409")
    void afterGraceJobExpiresAndFacadeAgrees() {
        Booking b = awaitingPayment(Duration.ofMinutes(16));

        assertThat(expiryJob.run().paymentsExpired()).isEqualTo(1);
        assertThat(statusOf(b.getId())).isEqualTo(BookingStatus.EXPIRED);

        assertThat(TrustedBookingSystemCall.run(TrustedBookingSystemCall.Purpose.PAYMENT_PROCESSING,
                () -> facade.markPaymentExpired(b.getId(), PAY_REF)).status()).isEqualTo(BookingStatus.EXPIRED);
        assertThatThrownBy(() -> TrustedBookingSystemCall.run(TrustedBookingSystemCall.Purpose.PAYMENT_PROCESSING,
                () -> facade.markPaymentConfirmed(b.getId(), PAY_REF)))
                .isInstanceOfSatisfying(ResourceConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("F5 after grace via WP07 first: facade expires it; the job finds nothing left to expire")
    void afterGraceFacadeFirst() {
        Booking b = awaitingPayment(Duration.ofMinutes(20));

        assertThat(TrustedBookingSystemCall.run(TrustedBookingSystemCall.Purpose.PAYMENT_PROCESSING,
                () -> facade.markPaymentExpired(b.getId(), PAY_REF)).status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(expiryJob.run().paymentsExpired()).isZero();
        assertThat(statusOf(b.getId())).isEqualTo(BookingStatus.EXPIRED);
    }

    // ---------- Reschedule: expiry of unanswered requests (real MongoDB query) ----------

    @Test
    @DisplayName("Reschedule: only PENDING requests past their deadline are expired by the job; booking keeps its time")
    void expiresUnansweredRescheduleRequests() {
        Booking late = save("cust-r", "prov-r", BookingStatus.CONFIRMED, SAME_SLOT);
        late.getRescheduleRequests().add(rescheduleRequest(RescheduleStatus.PENDING, NOW.minusSeconds(60)));
        bookingRepository.save(late);
        Booking stillOpen = save("cust-r", "prov-r", BookingStatus.CONFIRMED, SAME_SLOT.plus(Duration.ofDays(1)));
        stillOpen.getRescheduleRequests().add(rescheduleRequest(RescheduleStatus.PENDING, NOW.plusSeconds(3600)));
        bookingRepository.save(stillOpen);
        Booking answered = save("cust-r", "prov-r", BookingStatus.CONFIRMED, SAME_SLOT.plus(Duration.ofDays(2)));
        answered.getRescheduleRequests().add(rescheduleRequest(RescheduleStatus.REJECTED, NOW.minusSeconds(60)));
        bookingRepository.save(answered);

        assertThat(bookingRepository.findWithExpiredRescheduleRequests(NOW, PageRequest.of(0, 100)))
                .extracting(Booking::getId).containsExactly(late.getId());
        assertThat(expiryJob.run().reschedulesExpired()).isEqualTo(1);

        Booking stored = bookingRepository.findById(late.getId()).orElseThrow();
        assertThat(stored.getRescheduleRequests().get(0).getStatus()).isEqualTo(RescheduleStatus.EXPIRED);
        assertThat(stored.getSlotStart()).isEqualTo(SAME_SLOT);
        assertThat(bookingRepository.findById(stillOpen.getId()).orElseThrow().pendingReschedule()).isPresent();
        assertThat(expiryJob.run().reschedulesExpired()).isZero();
    }

    private static RescheduleRequest rescheduleRequest(RescheduleStatus status, Instant expiresAt) {
        RescheduleRequest r = new RescheduleRequest();
        r.setId(UUID.randomUUID().toString());
        r.setRequestedBy("cust-r");
        r.setRequestedByRole("CUSTOMER");
        r.setPreviousSlotStart(SAME_SLOT);
        r.setProposedSlotStart(SAME_SLOT.plus(Duration.ofDays(3)));
        r.setStatus(status);
        r.setRequestedAt(NOW.minus(Duration.ofHours(12)));
        r.setExpiresAt(expiresAt);
        return r;
    }

    // ---------- Block a date with bookings (real MongoDB, real availability module) ----------

    @Autowired
    private AvailabilityService availabilityService;

    @Autowired
    private BookingBlockDateService blockDateService;

    @Autowired
    private AvailabilityFacade availabilityFacade;

    @Test
    @DisplayName("Block a date: plain availability POST is refused while bookings exist; the booking flow keeps the "
            + "confirmed booking, declines the pending one and blocks the date")
    void blockDateWithBookings() {
        LocalDate day = LocalDate.now(SlotServiceImpl.BUSINESS_ZONE).plusDays(10);
        String provider = "prov-block-" + UUID.randomUUID();
        Instant nine = day.atTime(9, 0).atZone(SlotServiceImpl.BUSINESS_ZONE).toInstant();
        Booking confirmed = save("cust-b1", provider, BookingStatus.CONFIRMED, nine);
        Booking pending = save("cust-b2", provider, BookingStatus.PENDING_PROVIDER, nine.plus(Duration.ofHours(4)));
        CreateBlockedDateRequest plain = new CreateBlockedDateRequest(day.toString(), BlockReason.PERSONAL, null);

        assertThatThrownBy(() -> availabilityService.createBlockedDate(provider, plain))
                .isInstanceOfSatisfying(ResourceConflictException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("BOOKINGS_ON_DATE"));
        assertThat(availabilityFacade.isDateBlocked(provider, day)).isFalse();

        loginAs(provider, Role.PROVIDER);
        BlockDateResult result = blockDateService.blockWithBookings(new BlockDateWithBookingsRequest(day.toString(),
                BlockReason.PERSONAL, "Family function",
                List.of(new BlockDateDecision(confirmed.getId(), BlockDateAction.KEEP, null, null))));

        assertThat(result.keptBookingIds()).containsExactly(confirmed.getId());
        assertThat(result.declinedBookingIds()).containsExactly(pending.getId());
        assertThat(availabilityFacade.isDateBlocked(provider, day)).isTrue();
        assertThat(statusOf(confirmed.getId())).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(statusOf(pending.getId())).isEqualTo(BookingStatus.DECLINED);
    }
}

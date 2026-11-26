package com.gamagerecruiters.marketplace.modules.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingPriceSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.BookingSummary;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.BookingUserStats;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.DailyCount;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.LateStart;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.MonthlyCount;
import com.gamagerecruiters.marketplace.modules.booking.dto.stats.RepeatCancellation;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;

/**
 * Booking stats (WP09) and the My Bookings page queries against a real MongoDB (Testcontainers).
 * Runs as a background job (no user, no HTTP request). Requires Docker; skip with -DSKIP_DOCKER_TESTS=true.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
class BookingStatsMongoIntegrationTest {

    /** 2026-11-20 11:30 Asia/Colombo. */
    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");

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

    private BookingStatsFacadeImpl stats;

    @BeforeEach
    void setUp() {
        // Spring's test listener opens a mock HTTP request for every test; remove it so calls run as a background job.
        RequestContextHolder.resetRequestAttributes();
        mongoTemplate.remove(new Query(), Booking.class);
        stats = new BookingStatsFacadeImpl(mongoTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private Booking save(String customerId, String providerId, BookingStatus status, Instant slotStart,
                         Instant createdAt, BookingStatusChange... timeline) {
        Booking b = new Booking();
        b.setRef("BK-STATS-" + UUID.randomUUID());
        b.setCustomerId(customerId);
        b.setProviderId(providerId);
        b.setListingId("lst-1");
        b.setSlotStart(slotStart);
        b.setSlotEnd(slotStart.plus(Duration.ofHours(2)));
        b.setDurationMinutes(120);
        b.setStatus(status);
        b.setCreatedAt(createdAt);
        b.setUpdatedAt(createdAt);
        b.setTimeline(new ArrayList<>(List.of(timeline)));
        return mongoTemplate.insert(b);
    }

    private static BookingStatusChange change(BookingStatus from, BookingStatus to, Instant at) {
        return new BookingStatusChange(from, to, at, "someone", null);
    }

    private static Instant daysAgo(long days) {
        return NOW.minus(Duration.ofDays(days));
    }

    @Test
    @DisplayName("User stats: totals, ever-disputed, last 6 months with zeros, next 2 upcoming bookings")
    void userStats() {
        save("cust-1", "prov-1", BookingStatus.COMPLETED, daysAgo(5), Instant.parse("2026-11-02T04:00:00Z"));
        save("cust-1", "prov-1", BookingStatus.CANCELLED_BY_CUSTOMER, daysAgo(30), Instant.parse("2026-10-10T04:00:00Z"),
                change(BookingStatus.CONFIRMED, BookingStatus.CANCELLED_BY_CUSTOMER, daysAgo(31)));
        save("cust-1", "prov-1", BookingStatus.COMPLETED, daysAgo(60), Instant.parse("2026-09-10T04:00:00Z"),
                change(BookingStatus.COMPLETION_PENDING, BookingStatus.DISPUTED, daysAgo(59)),
                change(BookingStatus.DISPUTED, BookingStatus.COMPLETED, daysAgo(50)));
        Booking dec1 = save("cust-1", "prov-2", BookingStatus.CONFIRMED, Instant.parse("2026-12-01T04:30:00Z"),
                Instant.parse("2026-11-15T04:00:00Z"));
        Booking nov25 = save("cust-1", "prov-2", BookingStatus.PENDING_PROVIDER, Instant.parse("2026-11-25T04:30:00Z"),
                Instant.parse("2026-11-19T04:00:00Z"));
        save("cust-1", "prov-2", BookingStatus.EXPIRED, Instant.parse("2026-11-26T04:30:00Z"),
                Instant.parse("2026-11-18T04:00:00Z"));
        save("cust-1", "prov-1", BookingStatus.DECLINED, daysAgo(200), Instant.parse("2026-05-10T04:00:00Z"));
        save("cust-2", "prov-1", BookingStatus.CONFIRMED, Instant.parse("2026-11-24T04:30:00Z"), NOW);

        BookingUserStats s = stats.getUserStats("cust-1");

        assertThat(s.total()).isEqualTo(7);
        assertThat(s.completed()).isEqualTo(2);
        assertThat(s.cancelled()).isEqualTo(1);
        assertThat(s.disputed()).isEqualTo(1);
        assertThat(s.lastSixMonths()).containsExactly(
                new MonthlyCount(YearMonth.of(2026, 6), 0), new MonthlyCount(YearMonth.of(2026, 7), 0),
                new MonthlyCount(YearMonth.of(2026, 8), 0), new MonthlyCount(YearMonth.of(2026, 9), 1),
                new MonthlyCount(YearMonth.of(2026, 10), 1), new MonthlyCount(YearMonth.of(2026, 11), 4));
        assertThat(s.nextBookings()).extracting(BookingSummary::bookingId)
                .containsExactly(nov25.getId(), dec1.getId());

        assertThat(stats.getUserStats("prov-1").total()).isEqualTo(5);
    }

    @Test
    @DisplayName("Overdue: CONFIRMED / IN_PROGRESS whose slot already ended, oldest first, limited")
    void overdue() {
        Booking yesterday = save("c", "p", BookingStatus.CONFIRMED, daysAgo(1), daysAgo(5));
        Booking twoDays = save("c", "p", BookingStatus.IN_PROGRESS, daysAgo(2), daysAgo(5));
        save("c", "p", BookingStatus.CONFIRMED, NOW.plus(Duration.ofDays(1)), daysAgo(5));
        save("c", "p", BookingStatus.COMPLETED, daysAgo(3), daysAgo(5));
        save("c", "p", BookingStatus.CONFIRMED, NOW.minus(Duration.ofHours(1)), daysAgo(5)); // still running

        assertThat(stats.findOverdueBookings(10)).extracting(BookingSummary::bookingId)
                .containsExactly(twoDays.getId(), yesterday.getId());
        assertThat(stats.findOverdueBookings(1)).hasSize(1);
    }

    @Test
    @DisplayName("Repeat cancellations: counted per canceller inside the window, most first")
    void repeatCancellations() {
        for (int i = 0; i < 3; i++) {
            save("cust-A", "prov-X", BookingStatus.CANCELLED_BY_CUSTOMER, daysAgo(5), daysAgo(20),
                    change(BookingStatus.CONFIRMED, BookingStatus.CANCELLED_BY_CUSTOMER, daysAgo(i + 1)));
        }
        save("cust-A", "prov-X", BookingStatus.CANCELLED_BY_CUSTOMER, daysAgo(40), daysAgo(50),
                change(BookingStatus.CONFIRMED, BookingStatus.CANCELLED_BY_CUSTOMER, daysAgo(40)));
        for (int i = 0; i < 2; i++) {
            save("cust-" + i, "prov-B", BookingStatus.CANCELLED_BY_PROVIDER, daysAgo(5), daysAgo(20),
                    change(BookingStatus.CONFIRMED, BookingStatus.CANCELLED_BY_PROVIDER, daysAgo(10)));
        }
        save("cust-C", "prov-X", BookingStatus.CANCELLED_BY_CUSTOMER, daysAgo(5), daysAgo(20),
                change(BookingStatus.CONFIRMED, BookingStatus.CANCELLED_BY_CUSTOMER, daysAgo(3)));

        List<RepeatCancellation> result = stats.findRepeatCancellations(2, 30);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).userId()).isEqualTo("cust-A");
        assertThat(result.get(0).role()).isEqualTo("CUSTOMER");
        assertThat(result.get(0).count()).isEqualTo(3);
        assertThat(result.get(0).lastCancelledAt()).isEqualTo(daysAgo(1));
        assertThat(result.get(1).userId()).isEqualTo("prov-B");
        assertThat(result.get(1).role()).isEqualTo("PROVIDER");
        assertThat(result.get(1).count()).isEqualTo(2);
        // cust-A's 4th booking was created and cancelled 40+ days ago: outside the window, not in the total.
        assertThat(result.get(0).totalBookings()).isEqualTo(3);
        assertThat(result.get(1).totalBookings()).isEqualTo(2);
    }

    @Test
    @DisplayName("Repeat cancellations: totalBookings counts the user's bookings in the window (same role) for the rate")
    void repeatCancellationTotals() {
        for (int i = 0; i < 2; i++) {
            save("cust-A", "prov-X", BookingStatus.CANCELLED_BY_CUSTOMER, daysAgo(5), daysAgo(40),
                    change(BookingStatus.CONFIRMED, BookingStatus.CANCELLED_BY_CUSTOMER, daysAgo(i + 2)));
        }
        save("cust-A", "prov-X", BookingStatus.COMPLETED, daysAgo(4), daysAgo(10));   // created in window
        save("cust-A", "prov-X", BookingStatus.CONFIRMED, daysAgo(-3), daysAgo(1));    // created in window
        save("cust-A", "prov-X", BookingStatus.COMPLETED, daysAgo(60), daysAgo(70));   // before the window
        save("prov-X", "cust-A", BookingStatus.COMPLETED, daysAgo(4), daysAgo(10));    // cust-A as PROVIDER: not counted

        List<RepeatCancellation> result = stats.findRepeatCancellations(2, 30);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo("cust-A");
        assertThat(result.get(0).count()).isEqualTo(2);
        // 2 cancelled in the window (created before it) + 2 created in the window = 4 → rate 50%.
        assertThat(result.get(0).totalBookings()).isEqualTo(4);
    }

    private Booking inCategory(Booking booking, String categoryId) {
        booking.setPriceSnapshot(new BookingPriceSnapshot("Service " + categoryId, categoryId, "FIXED",
                new BigDecimal("1000.00"), List.of(), new BigDecimal("1000.00"), "LKR", new BigDecimal("0.10"), 120,
                booking.getCreatedAt()));
        return mongoTemplate.save(booking);
    }

    @Test
    @DisplayName("Late starts: CONFIRMED and past slotStart only, most late first, minutesLate, limit")
    void lateStarts() {
        Booking late90 = inCategory(save("c", "p", BookingStatus.CONFIRMED, NOW.minus(Duration.ofMinutes(90)),
                daysAgo(3)), "cat-A");
        Booking late10 = inCategory(save("c", "p", BookingStatus.CONFIRMED, NOW.minus(Duration.ofMinutes(10)),
                daysAgo(3)), "cat-B");
        save("c", "p", BookingStatus.CONFIRMED, NOW.plus(Duration.ofMinutes(30)), daysAgo(3));       // future
        save("c", "p", BookingStatus.IN_PROGRESS, NOW.minus(Duration.ofMinutes(60)), daysAgo(3));    // started
        save("c", "p", BookingStatus.AWAITING_PAYMENT, NOW.minus(Duration.ofMinutes(60)), daysAgo(3)); // not booked

        List<LateStart> result = stats.findLateStarts(10, null);

        assertThat(result).extracting(LateStart::bookingId).containsExactly(late90.getId(), late10.getId());
        assertThat(result).extracting(LateStart::minutesLate).containsExactly(90L, 10L);
        assertThat(result.get(0).serviceTitle()).isEqualTo("Service cat-A");
        assertThat(result.get(0).categoryId()).isEqualTo("cat-A");
        assertThat(stats.findLateStarts(1, null)).hasSize(1);
    }

    @Test
    @DisplayName("Category filter: late starts and overdue lists return only the chosen category; BookingSummary has it")
    void categoryFilter() {
        inCategory(save("c", "p", BookingStatus.CONFIRMED, NOW.minus(Duration.ofMinutes(30)), daysAgo(3)), "cat-A");
        Booking lateB = inCategory(save("c", "p", BookingStatus.CONFIRMED, NOW.minus(Duration.ofMinutes(20)),
                daysAgo(3)), "cat-B");
        inCategory(save("c", "p", BookingStatus.CONFIRMED, daysAgo(1), daysAgo(3)), "cat-A");
        Booking overdueB = inCategory(save("c", "p", BookingStatus.IN_PROGRESS, daysAgo(2), daysAgo(3)), "cat-B");

        assertThat(stats.findLateStarts(10, "cat-B")).extracting(LateStart::bookingId)
                .containsExactly(lateB.getId());
        List<BookingSummary> overdue = stats.findOverdueBookings(10, "cat-B");
        assertThat(overdue).extracting(BookingSummary::bookingId).containsExactly(overdueB.getId());
        assertThat(overdue.get(0).categoryId()).isEqualTo("cat-B");
        assertThat(stats.findOverdueBookings(10, " ")).hasSize(2);
        assertThat(stats.findLateStarts(10, "cat-none")).isEmpty();
    }

    @Test
    @DisplayName("Count by status: every status present, real counts from an aggregation")
    void countByStatus() {
        save("c", "p", BookingStatus.CONFIRMED, NOW, NOW);
        save("c", "p", BookingStatus.CONFIRMED, NOW, NOW);
        save("c", "p", BookingStatus.COMPLETED, NOW, NOW);

        Map<BookingStatus, Long> counts = stats.countByStatus();

        assertThat(counts).hasSize(BookingStatus.values().length);
        assertThat(counts.get(BookingStatus.CONFIRMED)).isEqualTo(2);
        assertThat(counts.get(BookingStatus.COMPLETED)).isEqualTo(1);
        assertThat(counts.get(BookingStatus.DECLINED)).isZero();
    }

    @Test
    @DisplayName("Created per day uses Colombo days (UTC 18:00 is 23:30, 19:00 is next day 00:30)")
    void createdPerDay() {
        save("c", "p", BookingStatus.CONFIRMED, NOW, Instant.parse("2026-11-18T18:00:00Z")); // 18th 23:30
        save("c", "p", BookingStatus.CONFIRMED, NOW, Instant.parse("2026-11-18T19:00:00Z")); // 19th 00:30
        save("c", "p", BookingStatus.CONFIRMED, NOW, Instant.parse("2026-11-20T04:00:00Z"));
        save("c", "p", BookingStatus.CONFIRMED, NOW, Instant.parse("2026-11-21T04:00:00Z")); // outside

        List<DailyCount> days = stats.countCreatedPerDay(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 20));

        assertThat(days).containsExactly(
                new DailyCount(LocalDate.of(2026, 11, 17), 0), new DailyCount(LocalDate.of(2026, 11, 18), 1),
                new DailyCount(LocalDate.of(2026, 11, 19), 1), new DailyCount(LocalDate.of(2026, 11, 20), 1));
    }

    @Test
    @DisplayName("My Bookings queries: provider page with status filter, newest slot first")
    void providerPageQuery() {
        save("c", "prov-1", BookingStatus.CONFIRMED, Instant.parse("2026-11-21T04:30:00Z"), NOW);
        Booking latest = save("c", "prov-1", BookingStatus.PENDING_PROVIDER, Instant.parse("2026-11-28T04:30:00Z"), NOW);
        save("c", "prov-1", BookingStatus.CONFIRMED, Instant.parse("2026-11-25T04:30:00Z"), NOW);
        save("c", "prov-1", BookingStatus.DECLINED, Instant.parse("2026-11-29T04:30:00Z"), NOW);
        save("c", "prov-2", BookingStatus.CONFIRMED, Instant.parse("2026-11-30T04:30:00Z"), NOW);

        Page<Booking> page = bookingRepository.findByProviderIdAndStatusIn("prov-1",
                EnumSet.of(BookingStatus.PENDING_PROVIDER, BookingStatus.CONFIRMED),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "slotStart")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent().get(0).getId()).isEqualTo(latest.getId());
        assertThat(page.getContent().get(1).getSlotStart()).isEqualTo(Instant.parse("2026-11-25T04:30:00Z"));
        assertThat(bookingRepository.findByCustomerId("c", PageRequest.of(0, 10)).getTotalElements()).isEqualTo(5);
    }
}

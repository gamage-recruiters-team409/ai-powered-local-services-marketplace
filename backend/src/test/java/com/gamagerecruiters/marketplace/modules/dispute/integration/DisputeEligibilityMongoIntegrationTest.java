package com.gamagerecruiters.marketplace.modules.dispute.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;

/**
 * Raise flow with the REAL eligibility check: real booking documents in a throw-away Testcontainers MongoDB, the real
 * BookingFacade (spied only to verify calls) and the real BookingFacadeDisputeEligibilityAdapter. The 72-hour rules
 * are the Booking module's; the dispute module adds none. Refuses to run against a database named "marketplace".
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@TestPropertySource(properties = {
        "app.dispute.booking-sync.enabled=false",
        "app.dispute.finalizer.enabled=false",
        "app.announcement.publisher.enabled=false"
})
class DisputeEligibilityMongoIntegrationTest {

    private static final String TEST_DATABASE = "dispute_eligibility_it";
    private static final String FORBIDDEN_DATABASE = "marketplace";
    private static final String BOOKING_ID = "booking-elig-1";
    private static final String CUSTOMER_ID = "customer-elig";
    private static final String PROVIDER_ID = "provider-elig";

    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void configureMongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> {
            if (!mongoDBContainer.isRunning()) {
                mongoDBContainer.start();
            }
            return mongoDBContainer.getReplicaSetUrl(TEST_DATABASE);
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

    /** The real Booking module; spied only to verify that markDisputed is (not) called. */
    @MockitoSpyBean
    private BookingFacade bookingFacade;
    @Autowired
    private DisputeService disputeService;
    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void refuseSharedDatabaseAndClean() {
        String dbName = mongoTemplate.getDb().getName();
        if (FORBIDDEN_DATABASE.equals(dbName)) {
            throw new IllegalStateException("Refusing to run: connected to the database named \"" + FORBIDDEN_DATABASE
                    + "\". This test deletes data and must only run against a throw-away database.");
        }
        assertThat(dbName).isEqualTo(TEST_DATABASE);
        mongoTemplate.remove(new Query(), Dispute.class);
        mongoTemplate.remove(new Query(), Booking.class);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ---------- helpers ----------

    private static void loggedInAs(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@test.local", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** A booking whose timeline walks CONFIRMED -> ... -> {@code status}; the last change happened {@code ago}. */
    private void booking(BookingStatus status, Duration ago) {
        Instant last = Instant.now().minus(ago);
        List<BookingStatus> path = switch (status) {
            case CONFIRMED -> List.of(BookingStatus.CONFIRMED);
            case IN_PROGRESS -> List.of(BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS);
            case COMPLETED -> List.of(BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS,
                    BookingStatus.COMPLETION_PENDING, BookingStatus.COMPLETED);
            case DISPUTED -> List.of(BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS, BookingStatus.DISPUTED);
            default -> throw new IllegalArgumentException(status.name());
        };
        List<BookingStatusChange> timeline = new ArrayList<>();
        BookingStatus from = BookingStatus.AWAITING_PAYMENT;
        for (int i = 0; i < path.size(); i++) {
            Instant at = last.minus(Duration.ofHours(path.size() - 1L - i));
            timeline.add(new BookingStatusChange(from, path.get(i), at, CUSTOMER_ID, "test"));
            from = path.get(i);
        }
        Booking booking = new Booking();
        booking.setId(BOOKING_ID);
        booking.setCustomerId(CUSTOMER_ID);
        booking.setProviderId(PROVIDER_ID);
        booking.setListingId("listing-elig");
        booking.setSlotStart(last.minus(Duration.ofHours(4)));
        booking.setSlotEnd(last.minus(Duration.ofHours(3)));
        booking.setStatus(status);
        booking.setTimeline(timeline);
        booking.setCreatedAt(last.minus(Duration.ofDays(2)));
        mongoTemplate.insert(booking);
    }

    private BookingStatus bookingStatus() {
        return mongoTemplate.findById(BOOKING_ID, Booking.class).getStatus();
    }

    private DisputeDetailResponse raiseAs(String userId, Role role, DisputeCategory category) {
        loggedInAs(userId, role);
        return disputeService.raiseDispute(userId, role,
                new RaiseDisputeRequest(BOOKING_ID, category, "Integration test issue text", List.of()));
    }

    private List<Dispute> stored() {
        return mongoTemplate.find(Query.query(Criteria.where("bookingId").is(BOOKING_ID)), Dispute.class);
    }

    private static void assertApiError(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    // ---------- (a) already DISPUTED within the window ----------

    @Test
    @DisplayName("(a) booking already DISPUTED 10h ago -> dispute saved and SYNCED; markDisputed NOT called again")
    void alreadyDisputedWithinWindow() {
        booking(BookingStatus.DISPUTED, Duration.ofHours(10));

        DisputeDetailResponse created = raiseAs(CUSTOMER_ID, Role.CUSTOMER, DisputeCategory.POOR_QUALITY);

        assertThat(stored()).singleElement().satisfies(d -> {
            assertThat(d.getId()).isEqualTo(created.id());
            assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.SYNCED);
        });
        verify(bookingFacade).assertDisputeEligible(BOOKING_ID, CUSTOMER_ID);
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
        assertThat(mongoTemplate.findById(BOOKING_ID, Booking.class).getTimeline()).as("booking unchanged").hasSize(3);
    }

    @Test
    @DisplayName("already DISPUTED for more than 72h -> 409 DISPUTE_WINDOW_CLOSED from the Booking module, nothing saved")
    void alreadyDisputedOutsideWindow() {
        booking(BookingStatus.DISPUTED, Duration.ofHours(73));

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER, DisputeCategory.POOR_QUALITY)),
                "DISPUTE_WINDOW_CLOSED");
        assertThat(stored()).isEmpty();
    }

    // ---------- (b) completed more than 72h ago ----------

    @Test
    @DisplayName("(b) COMPLETED 73h ago -> 409 DISPUTE_WINDOW_CLOSED, nothing saved, booking unchanged")
    void completedOutsideWindow() {
        booking(BookingStatus.COMPLETED, Duration.ofHours(73));

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER, DisputeCategory.POOR_QUALITY)),
                "DISPUTE_WINDOW_CLOSED");

        assertThat(stored()).isEmpty();
        assertThat(bookingStatus()).isEqualTo(BookingStatus.COMPLETED);
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("COMPLETED 71h ago -> accepted, booking moves to DISPUTED")
    void completedWithinWindow() {
        booking(BookingStatus.COMPLETED, Duration.ofHours(71));

        raiseAs(PROVIDER_ID, Role.PROVIDER, DisputeCategory.PRICING_OR_PAYMENT);

        assertThat(stored()).singleElement()
                .satisfies(d -> assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.SYNCED));
        assertThat(bookingStatus()).isEqualTo(BookingStatus.DISPUTED);
    }

    // ---------- (c) no-show before completion ----------

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "IN_PROGRESS"})
    @DisplayName("(c) NO_SHOW on a CONFIRMED / IN_PROGRESS booking (no completion yet, slot days ago) -> accepted; no "
            + "completion-based time check in the dispute module")
    void noShowBeforeCompletion(BookingStatus status) {
        booking(status, Duration.ofDays(5));

        DisputeDetailResponse created = raiseAs(CUSTOMER_ID, Role.CUSTOMER, DisputeCategory.NO_SHOW);

        assertThat(created.category()).isEqualTo(DisputeCategory.NO_SHOW);
        assertThat(stored()).singleElement()
                .satisfies(d -> assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.SYNCED));
        assertThat(bookingStatus()).isEqualTo(BookingStatus.DISPUTED);
        verify(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, "Dispute raised: NO_SHOW");
    }

    // ---------- (d) one dispute per booking ----------

    @Test
    @DisplayName("(d) second raise for the same booking (either party) -> 409 DISPUTE_ALREADY_EXISTS, one dispute")
    void secondRaiseWhileActive() {
        booking(BookingStatus.CONFIRMED, Duration.ofHours(1));
        raiseAs(CUSTOMER_ID, Role.CUSTOMER, DisputeCategory.NO_SHOW);

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER, DisputeCategory.NO_SHOW)),
                "DISPUTE_ALREADY_EXISTS");
        assertApiError(catchThrowable(() -> raiseAs(PROVIDER_ID, Role.PROVIDER, DisputeCategory.OTHER)),
                "DISPUTE_ALREADY_EXISTS");
        assertThat(stored()).hasSize(1);
    }

    @Test
    @DisplayName("(d) after finalization and settlement (active key released, booking COMPLETED again just now) -> a new "
            + "raise is still 409 DISPUTE_ALREADY_EXISTS and the booking is not touched")
    void secondRaiseAfterFinalization() {
        booking(BookingStatus.CONFIRMED, Duration.ofHours(1));
        String first = raiseAs(CUSTOMER_ID, Role.CUSTOMER, DisputeCategory.NO_SHOW).id();
        // As after RESOLVED_FINAL + booking resolution APPLIED: no active key, booking COMPLETED (inside BR-008's 72h).
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(first)),
                new Update().set("status", DisputeStatus.RESOLVED_FINAL).unset("activeBookingKey"), Dispute.class);
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(BOOKING_ID)), Booking.class);
        booking(BookingStatus.COMPLETED, Duration.ofMinutes(5));

        assertApiError(catchThrowable(() -> raiseAs(PROVIDER_ID, Role.PROVIDER, DisputeCategory.OTHER)),
                "DISPUTE_ALREADY_EXISTS");

        assertThat(stored()).singleElement().satisfies(d -> assertThat(d.getId()).isEqualTo(first));
        assertThat(bookingStatus()).isEqualTo(BookingStatus.COMPLETED);
    }

    // ---------- (e) PENDING intent recovery ----------

    @Test
    @DisplayName("(e) own PENDING intent and the booking is DISPUTED -> the retry completes the SAME record (SYNCED), "
            + "no second record, no second markDisputed")
    void pendingIntentRecovery() {
        booking(BookingStatus.DISPUTED, Duration.ofMinutes(2));
        Instant createdAt = Instant.now().minus(Duration.ofMinutes(2));
        Dispute intent = new Dispute();
        intent.setBookingId(BOOKING_ID);
        intent.setCustomerId(CUSTOMER_ID);
        intent.setProviderId(PROVIDER_ID);
        intent.setRaisedBy(CUSTOMER_ID);
        intent.setRaisedByRole(Role.CUSTOMER);
        intent.setCategory(DisputeCategory.NO_SHOW);
        intent.setDescription("Pending intent");
        intent.setCreatedAt(createdAt);
        intent.setResponseDueAt(createdAt.plus(DisputeRules.RESPONSE_WINDOW));
        intent.setBookingSyncState(BookingSyncState.PENDING);
        intent.changeStatus(DisputeStatus.AWAITING_RESPONSE, CUSTOMER_ID, Role.CUSTOMER, null, createdAt);
        String intentId = mongoTemplate.insert(intent).getId();

        DisputeDetailResponse retried = raiseAs(CUSTOMER_ID, Role.CUSTOMER, DisputeCategory.NO_SHOW);

        assertThat(retried.id()).isEqualTo(intentId);
        assertThat(stored()).singleElement()
                .satisfies(d -> assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.SYNCED));
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
    }
}

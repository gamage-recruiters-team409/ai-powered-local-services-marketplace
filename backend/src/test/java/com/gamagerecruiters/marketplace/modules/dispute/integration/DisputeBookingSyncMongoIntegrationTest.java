package com.gamagerecruiters.marketplace.modules.dispute.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeEligibilityPort;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeBookingSyncReconciler;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeBookingSyncStore;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;

/**
 * Booking/dispute consistency against a REAL MongoDB in a throw-away Testcontainers instance (never Atlas or the
 * shared database). The Booking module is replaced by a mocked BookingFacade; everything in the dispute module is
 * real, including the unique active-booking index created at startup.
 *
 * <p>Covers: (a) failure after the booking transition, then completion by the reconciler and by a retry;
 * (b) markDisputed rejected, so the intent record is deleted; (c) two concurrent raises, only one dispute exists;
 * (d) a PENDING record is invisible in lists and detail but still blocks a second dispute; (e) two simultaneous
 * requests of the SAME user, with controlled interleaving inside markDisputed: only one works on the record, it is
 * never removed, exactly one visible dispute remains; (f) markDisputed fails with an UNCERTAIN outcome (the booking
 * was saved before the error): the record is kept and the reconciler recovers a valid dispute; plus the reconciler's
 * delete path and its atomic, owner-tagged lease. Refuses to run against a database named "marketplace".</p>
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@TestPropertySource(properties = {
        // The test drives the reconciler itself; no background runs, and records are eligible immediately.
        "app.dispute.booking-sync.enabled=false",
        "app.dispute.finalizer.enabled=false",
        "app.dispute.booking-sync.min-age=PT0S",
        "app.dispute.booking-sync.delete-after=PT15M"
})
class DisputeBookingSyncMongoIntegrationTest {

    private static final String TEST_DATABASE = "dispute_it";
    private static final String FORBIDDEN_DATABASE = "marketplace";
    private static final String BOOKING_ID = "booking-it-1";
    private static final String CUSTOMER_ID = "customer-it";
    private static final String PROVIDER_ID = "provider-it";
    private static final String REASON = "Dispute raised: POOR_QUALITY";

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

    @MockitoBean
    private BookingFacade bookingFacade;
    /** Stands in for BookingFacade.assertDisputeEligible until it is merged (see BookingFacadeDisputeEligibilityAdapter). */
    @MockitoBean
    private DisputeEligibilityPort eligibilityPort;
    @MockitoSpyBean
    private DisputeBookingSyncStore syncStore;

    @Autowired
    private DisputeService disputeService;
    @Autowired
    private DisputeBookingSyncReconciler reconciler;
    @Autowired
    private MongoTemplate mongoTemplate;

    /** The booking's status as the (mocked) Booking module currently holds it. */
    private final AtomicReference<BookingStatus> bookingStatus = new AtomicReference<>(BookingStatus.CONFIRMED);

    @BeforeEach
    void refuseSharedDatabaseAndClean() {
        String dbName = mongoTemplate.getDb().getName();
        if (FORBIDDEN_DATABASE.equals(dbName)) {
            throw new IllegalStateException("Refusing to run: connected to the database named \"" + FORBIDDEN_DATABASE
                    + "\". This test deletes disputes and must only run against a throw-away database.");
        }
        assertThat(dbName).isEqualTo(TEST_DATABASE);
        // Remove documents only; the indexes created at startup (incl. the unique active-booking key) stay.
        mongoTemplate.remove(new Query(), Dispute.class);
        bookingIs(BookingStatus.CONFIRMED);
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

    /** The booking as the reconciler reads it (facade) and as the eligibility check returns it (eligible). */
    private void bookingIs(BookingStatus status) {
        bookingStatus.set(status);
        Instant now = Instant.now();
        when(bookingFacade.getBookingParticipants(BOOKING_ID)).thenAnswer(invocation -> new BookingParticipants(
                BOOKING_ID, "series-it", "listing-it", CUSTOMER_ID, PROVIDER_ID, bookingStatus.get(), now,
                now.plusSeconds(3600)));
        when(eligibilityPort.assertEligible(BOOKING_ID)).thenAnswer(invocation -> new BookingSnapshot(
                BOOKING_ID, CUSTOMER_ID, PROVIDER_ID, bookingStatus.get() == BookingStatus.DISPUTED));
    }

    private static RaiseDisputeRequest request() {
        return new RaiseDisputeRequest(BOOKING_ID, DisputeCategory.POOR_QUALITY, "Integration test issue text",
                List.of());
    }

    private DisputeDetailResponse raiseAs(String userId, Role role) {
        loggedInAs(userId, role);
        return disputeService.raiseDispute(userId, role, request());
    }

    private List<Dispute> stored() {
        return mongoTemplate.find(Query.query(Criteria.where("bookingId").is(BOOKING_ID)), Dispute.class);
    }

    private Dispute insertPending(String raisedBy, Duration age) {
        Instant createdAt = Instant.now().minus(age);
        Dispute dispute = new Dispute();
        dispute.setBookingId(BOOKING_ID);
        dispute.setCustomerId(CUSTOMER_ID);
        dispute.setProviderId(PROVIDER_ID);
        dispute.setRaisedBy(raisedBy);
        dispute.setRaisedByRole(Role.CUSTOMER);
        dispute.setCategory(DisputeCategory.NO_SHOW);
        dispute.setDescription("Pending intent");
        dispute.setCreatedAt(createdAt);
        dispute.setResponseDueAt(createdAt.plus(DisputeRules.RESPONSE_WINDOW));
        dispute.setBookingSyncState(BookingSyncState.PENDING);
        dispute.changeStatus(DisputeStatus.AWAITING_RESPONSE, raisedBy, Role.CUSTOMER, null, createdAt);
        return mongoTemplate.insert(dispute);
    }

    private static void assertApiError(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    // ---------- (a) failure after the booking transition ----------

    @Test
    @DisplayName("(a) SYNCED update fails after the booking moved -> PENDING stays invisible; the reconciler completes it")
    void failureAfterBookingTransitionIsReconciled() {
        doThrow(new DataAccessResourceFailureException("simulated outage"))
                .doCallRealMethod().when(syncStore).markSynced(anyString(), anyString());

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), "DISPUTE_SYNC_PENDING");

        List<Dispute> afterFailure = stored();
        assertThat(afterFailure).singleElement()
                .satisfies(d -> assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.PENDING));
        String disputeId = afterFailure.get(0).getId();
        verify(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, REASON);
        assertThat(disputeService.getMyDisputes(CUSTOMER_ID, 0, 20).content()).isEmpty();
        assertApiError(catchThrowable(() -> disputeService.getDisputeForAdmin(disputeId)), "DISPUTE_NOT_FOUND");

        // The booking is DISPUTED now (the facade call succeeded); the reconciler completes the record.
        bookingIs(BookingStatus.DISPUTED);
        DisputeBookingSyncReconciler.Result result = reconciler.reconcile();

        assertThat(result.synced()).isEqualTo(1);
        assertThat(stored()).singleElement()
                .satisfies(d -> assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.SYNCED));
        assertThat(disputeService.getMyDisputes(CUSTOMER_ID, 0, 20).content()).hasSize(1);
        assertThat(disputeService.getDisputeForAdmin(disputeId).id()).isEqualTo(disputeId);
    }

    @Test
    @DisplayName("(a) same failure, then the same user retries -> the same record is completed, booking moved only once")
    void failureAfterBookingTransitionIsCompletedByRetry() {
        doThrow(new DataAccessResourceFailureException("simulated outage"))
                .doCallRealMethod().when(syncStore).markSynced(anyString(), anyString());
        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), "DISPUTE_SYNC_PENDING");
        String disputeId = stored().get(0).getId();

        bookingIs(BookingStatus.DISPUTED);
        DisputeDetailResponse retried = raiseAs(CUSTOMER_ID, Role.CUSTOMER);

        assertThat(retried.id()).isEqualTo(disputeId);
        assertThat(stored()).singleElement()
                .satisfies(d -> assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.SYNCED));
        verify(bookingFacade, times(1)).markDisputed(anyString(), anyString(), anyString());
    }

    // ---------- (b) markDisputed rejected ----------

    @Test
    @DisplayName("(b) markDisputed rejected -> the facade error is returned and the intent record is deleted")
    void rejectedTransitionDeletesIntent() {
        ResourceConflictException closed = new ResourceConflictException("DISPUTE_WINDOW_CLOSED", "closed");
        when(bookingFacade.markDisputed(BOOKING_ID, CUSTOMER_ID, REASON)).thenThrow(closed);

        assertThat(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER))).isSameAs(closed);

        assertThat(stored()).isEmpty();
        // The unique key is free again: a later, valid raise works.
        doReturn(null).when(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, REASON);
        assertThat(raiseAs(CUSTOMER_ID, Role.CUSTOMER).bookingId()).isEqualTo(BOOKING_ID);
    }

    @Test
    @DisplayName("eligibility rejected (e.g. already DISPUTED for more than 72h) -> error unchanged, nothing stored")
    void ineligibleRequestStoresNothing() {
        ResourceConflictException closed = new ResourceConflictException("DISPUTE_WINDOW_CLOSED", "closed");
        when(eligibilityPort.assertEligible(BOOKING_ID)).thenThrow(closed);

        assertThat(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER))).isSameAs(closed);

        assertThat(stored()).isEmpty();
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("legitimate already-DISPUTED booking -> dispute created and SYNCED without a second markDisputed")
    void alreadyDisputedEligibleIsCreated() {
        bookingIs(BookingStatus.DISPUTED);

        raiseAs(CUSTOMER_ID, Role.CUSTOMER);

        assertThat(stored()).singleElement()
                .satisfies(d -> assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.SYNCED));
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
    }

    // ---------- (c) concurrency ----------

    @Test
    @DisplayName("(c) customer and provider raise at the same moment -> exactly one dispute exists, the other gets 409")
    void concurrentRaisesCreateOneDispute() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> customer = raiseTask(start, CUSTOMER_ID, Role.CUSTOMER);
            Callable<Object> provider = raiseTask(start, PROVIDER_ID, Role.PROVIDER);
            List<Future<Object>> futures = new ArrayList<>(List.of(pool.submit(customer), pool.submit(provider)));
            start.countDown();

            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(outcomes).filteredOn(DisputeDetailResponse.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(ApiException.class::isInstance).singleElement()
                    .satisfies(e -> assertThat(((ApiException) e).getErrorCode()).isEqualTo("DISPUTE_ALREADY_EXISTS"));
            assertThat(stored()).singleElement()
                    .satisfies(d -> assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.SYNCED));
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Object> raiseTask(CountDownLatch start, String userId, Role role) {
        return () -> {
            start.await();
            try {
                return raiseAs(userId, role);
            } catch (ApiException e) {
                return e;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    // ---------- (d) PENDING is invisible ----------

    @Test
    @DisplayName("(d) a PENDING record is invisible in every list and detail view, but still blocks a second dispute")
    void pendingIsInvisible() {
        String pendingId = insertPending(PROVIDER_ID, Duration.ofMinutes(1)).getId();

        assertThat(disputeService.getMyDisputes(CUSTOMER_ID, 0, 20).content()).isEmpty();
        assertThat(disputeService.getMyDisputes(PROVIDER_ID, 0, 20).content()).isEmpty();
        assertThat(disputeService.getDisputesForAdmin(null, 0, 20).content()).isEmpty();
        assertThat(disputeService.getDisputesForAdmin("AWAITING_RESPONSE", 0, 20).totalElements()).isZero();
        assertApiError(catchThrowable(() -> disputeService.getDisputeForParty(CUSTOMER_ID, pendingId)),
                "DISPUTE_NOT_FOUND");
        assertApiError(catchThrowable(() -> disputeService.getDisputeForAdmin(pendingId)), "DISPUTE_NOT_FOUND");

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), "DISPUTE_ALREADY_EXISTS");
        verify(bookingFacade, never()).markDisputed(anyString(), anyString(), anyString());
    }

    // ---------- (e) two simultaneous requests of the SAME user ----------

    /**
     * The first markDisputed call blocks INSIDE the Booking module until released, then moves the booking to DISPUTED.
     * Any further call gets what the real facade answers once the booking is DISPUTED (409 INVALID_STATUS_TRANSITION),
     * which is exactly the error that, before the fix, made a second request delete the shared record.
     */
    private CountDownLatch[] blockFirstMarkDisputed(AtomicInteger calls) {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test gate not released");
                }
                bookingStatus.set(BookingStatus.DISPUTED);
                return null;
            }
            throw new ResourceConflictException("INVALID_STATUS_TRANSITION", "Booking cannot move from DISPUTED");
        }).when(bookingFacade).markDisputed(eq(BOOKING_ID), eq(CUSTOMER_ID), anyString());
        return new CountDownLatch[] {entered, release};
    }

    /**
     * Request A is held inside markDisputed; request B (same user) runs completely meanwhile; then A is released.
     * Returns B's outcome; asserts A succeeded.
     */
    private Object interleaveSameUser(AtomicInteger calls) throws Exception {
        CountDownLatch[] gate = blockFirstMarkDisputed(calls);
        CountDownLatch go = new CountDownLatch(0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(raiseTask(go, CUSTOMER_ID, Role.CUSTOMER));
            assertThat(gate[0].await(30, TimeUnit.SECONDS)).as("request A is inside markDisputed").isTrue();

            Object second = pool.submit(raiseTask(go, CUSTOMER_ID, Role.CUSTOMER)).get(30, TimeUnit.SECONDS);
            // While A is still working, B changed nothing: the record is still there and still PENDING.
            assertThat(stored()).singleElement()
                    .satisfies(d -> assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.PENDING));

            gate[1].countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isInstanceOf(DisputeDetailResponse.class);
            return second;
        } finally {
            gate[1].countDown();
            pool.shutdownNow();
        }
    }

    private void assertExactlyOneVisibleDispute() {
        assertThat(stored()).singleElement()
                .satisfies(d -> assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.SYNCED));
        assertThat(disputeService.getMyDisputes(CUSTOMER_ID, 0, 20).content()).hasSize(1);
        assertThat(disputeService.getMyDisputes(PROVIDER_ID, 0, 20).content()).hasSize(1);
        assertThat(disputeService.getDisputesForAdmin(null, 0, 20).content()).hasSize(1);
    }

    @Test
    @DisplayName("(e) same user double-submits while the first request is inside markDisputed -> the second gets 503 "
            + "DISPUTE_SYNC_PENDING without touching the booking or the record; exactly one visible dispute")
    void sameUserDoubleSubmitIsSerialized() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        Object second = interleaveSameUser(calls);

        assertApiError((Throwable) second, "DISPUTE_SYNC_PENDING");
        assertThat(calls.get()).as("only one request moved the booking").isEqualTo(1);
        assertExactlyOneVisibleDispute();
    }

    @Test
    @DisplayName("(e) same user sends two simultaneous RETRIES of an existing PENDING intent -> only one takes it, the "
            + "other gets 503 and cannot remove it; the same record ends SYNCED, exactly one visible dispute")
    void sameUserConcurrentRetriesAreSerialized() throws Exception {
        String pendingId = insertPending(CUSTOMER_ID, Duration.ofMinutes(1)).getId();
        AtomicInteger calls = new AtomicInteger();

        Object second = interleaveSameUser(calls);

        assertApiError((Throwable) second, "DISPUTE_SYNC_PENDING");
        assertThat(calls.get()).as("only one request moved the booking").isEqualTo(1);
        assertExactlyOneVisibleDispute();
        assertThat(stored().get(0).getId()).isEqualTo(pendingId);
    }

    // ---------- (f) uncertain outcome of the booking update ----------

    @Test
    @DisplayName("(f) confirmed rejection (409 INVALID_STATUS_TRANSITION) but the re-read booking IS DISPUTED -> the "
            + "intent is NOT deleted; the dispute is completed and returned")
    void rejectedButBookingDisputedIsCompleted() {
        doAnswer(invocation -> {
            bookingStatus.set(BookingStatus.DISPUTED);
            throw new ResourceConflictException("INVALID_STATUS_TRANSITION", "Booking cannot move from DISPUTED");
        }).when(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, REASON);

        DisputeDetailResponse result = raiseAs(CUSTOMER_ID, Role.CUSTOMER);

        assertThat(result.bookingId()).isEqualTo(BOOKING_ID);
        assertExactlyOneVisibleDispute();
    }

    @Test
    @DisplayName("(f) booking SAVED as DISPUTED, then the facade reports a timeout -> record KEPT (not deleted), 503 "
            + "DISPUTE_SYNC_PENDING; the reconciler sees the booking is DISPUTED and recovers a valid dispute")
    void uncertainFailureAfterBookingSavedIsRecovered() {
        doAnswer(invocation -> {
            bookingStatus.set(BookingStatus.DISPUTED);
            throw new QueryTimeoutException("timed out waiting for the write acknowledgement");
        }).when(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, REASON);

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), "DISPUTE_SYNC_PENDING");

        Dispute kept = stored().get(0);
        assertThat(stored()).hasSize(1);
        assertThat(kept.getBookingSyncState()).isEqualTo(BookingSyncState.PENDING);
        assertThat(kept.getSyncLeaseOwner()).as("lease released for the reconciler").isNull();
        assertThat(disputeService.getMyDisputes(CUSTOMER_ID, 0, 20).content()).isEmpty();

        DisputeBookingSyncReconciler.Result result = reconciler.reconcile();

        assertThat(result.synced()).isEqualTo(1);
        assertThat(result.deleted()).isZero();
        assertExactlyOneVisibleDispute();
        assertThat(disputeService.getDisputeForParty(PROVIDER_ID, kept.getId()).id())
                .as("the counterparty can see and answer it").isEqualTo(kept.getId());
        verify(bookingFacade, times(1)).markDisputed(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("(f) booking NOT saved and the facade reports a timeout -> record kept while young; removed by the "
            + "reconciler only after it confirms the booking is not DISPUTED and delete-after has passed")
    void uncertainFailureNotAppliedIsRemovedOnlyAfterVerification() {
        doThrow(new QueryTimeoutException("timed out")).when(bookingFacade).markDisputed(BOOKING_ID, CUSTOMER_ID, REASON);

        assertApiError(catchThrowable(() -> raiseAs(CUSTOMER_ID, Role.CUSTOMER)), "DISPUTE_SYNC_PENDING");
        String id = stored().get(0).getId();

        assertThat(reconciler.reconcile().deleted()).as("too young to judge").isZero();
        assertThat(stored()).hasSize(1);

        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
                // Older than delete-after, and the earlier run's lease has expired.
                new Update().set("createdAt", Instant.now().minus(Duration.ofMinutes(30)))
                        .unset("syncLeaseUntil").unset("syncLeaseOwner"), Dispute.class);
        assertThat(reconciler.reconcile().deleted()).isEqualTo(1);
        assertThat(stored()).isEmpty();
        assertThat(bookingStatus.get()).isEqualTo(BookingStatus.CONFIRMED);
    }

    // ---------- reconciler: delete path and multi-instance safety ----------

    @Test
    @DisplayName("reconciler: booking not DISPUTED and the record older than delete-after -> removed; young ones kept")
    void reconcilerRemovesStaleIntent() {
        insertPending(CUSTOMER_ID, Duration.ofMinutes(30));

        assertThat(reconciler.reconcile().deleted()).isEqualTo(1);
        assertThat(stored()).isEmpty();

        insertPending(CUSTOMER_ID, Duration.ofMinutes(1));
        assertThat(reconciler.reconcile().deleted()).isZero();
        assertThat(stored()).hasSize(1);
    }

    @Test
    @DisplayName("reconciler: a claimed record is leased atomically, so a second instance cannot claim it at the same time")
    void claimIsAtomicAcrossInstances() {
        insertPending(CUSTOMER_ID, Duration.ofMinutes(30));
        Instant now = Instant.now();

        Dispute first = syncStore.claimPending(now, now, Duration.ofMinutes(5)).orElseThrow();
        assertThat(syncStore.claimPending(now, now, Duration.ofMinutes(5))).as("second instance").isEmpty();
        assertThat(syncStore.claimById(first.getId(), now, Duration.ofMinutes(5))).as("a raise request").isEmpty();
        // After the lease expires it can be claimed again, with a new token.
        Dispute second = syncStore.claimPending(now, now.plus(Duration.ofMinutes(6)), Duration.ofMinutes(5)).orElseThrow();
        assertThat(second.getSyncLeaseOwner()).isNotEqualTo(first.getSyncLeaseOwner());
        // The worker whose lease expired can no longer remove the record; the current holder can.
        assertThat(syncStore.deleteIfPending(first.getId(), first.getSyncLeaseOwner())).isFalse();
        assertThat(stored()).hasSize(1);
        assertThat(syncStore.deleteIfPending(second.getId(), second.getSyncLeaseOwner())).isTrue();
    }

    @Test
    @DisplayName("completion is applied once and only by the lease holder; a SYNCED record is never removed")
    void markSyncedIsConditional() {
        String id = insertPending(CUSTOMER_ID, Duration.ofMinutes(1)).getId();
        String owner = syncStore.claimById(id, Instant.now(), Duration.ofMinutes(5)).orElseThrow().getSyncLeaseOwner();

        assertThat(syncStore.markSynced(id, "not-the-holder")).as("another worker's token").isEmpty();
        assertThat(syncStore.markSynced(id, owner)).isPresent();
        assertThat(syncStore.markSynced(id, owner)).isEmpty();
        assertThat(syncStore.deleteIfPending(id, owner)).as("a SYNCED dispute is never removed").isFalse();
        assertThatThrownBy(() -> disputeService.getDisputeForAdmin("missing-id")).isInstanceOf(ApiException.class);
        verify(bookingFacade, never()).markDisputed(eq(BOOKING_ID), any(), anyString());
    }
}

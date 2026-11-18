package com.gamagerecruiters.marketplace.modules.completion.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.domain.CompletionConfirmationType;
import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;
import com.gamagerecruiters.marketplace.modules.completion.dto.MarkCompletedRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.RaiseIssueRequest;
import com.gamagerecruiters.marketplace.modules.completion.repository.ServiceCompletionRepository;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionAtomicUpdates;
import com.gamagerecruiters.marketplace.modules.completion.service.ServiceCompletionService;

/**
 * Real MongoDB (Testcontainers replica set, so multi-document transactions run for real).
 *
 * <p>1. Rollback: a failure after BookingFacade has already saved the booking must leave BOTH the
 * booking and the completion record unchanged, for start, mark-completed, confirm, raise-issue
 * and auto-confirm. The failure is injected with a spy on the real BookingFacade.
 *
 * <p>2. Concurrency: customer confirmation vs issue raising, and customer confirmation vs
 * auto-confirmation, sent at the same moment many times. Exactly one decision wins, the loser
 * gets 409 (or the job skips), and the booking always matches the completion record.
 *
 * <p>3. A failed request can never overwrite a decision that was already committed.
 */
@SpringBootTest(properties = "app.completion.auto-confirm.enabled=false")
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "true")
class CompletionConsistencyIntegrationTest {

    private static final String CUSTOMER = "it-customer-1";
    private static final String PROVIDER = "it-provider-1";
    private static final int ROUNDS = 15;

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
        if (mongoDBContainer != null && mongoDBContainer.isRunning()) {
            mongoDBContainer.stop();
        }
    }

    @Autowired
    private ServiceCompletionService completionService;

    @Autowired
    private CompletionAtomicUpdates atomicUpdates;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private ServiceCompletionRepository completionRepository;

    /** The real BookingFacade; individual tests make it fail after it has saved the booking. */
    @MockitoSpyBean
    private BookingFacade bookingFacade;

    @BeforeEach
    void clean() {
        completionRepository.deleteAll();
        bookingRepository.deleteAll();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
        reset(bookingFacade);
    }

    // ------------------------------------------------------------------ helpers

    private static void signIn(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@example.com", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Booking saveBooking(BookingStatus status, Instant pendingSince) {
        Instant now = Instant.now();
        Booking booking = new Booking();
        booking.setId("it-" + UUID.randomUUID());
        booking.setRef("BK-IT-" + UUID.randomUUID());
        booking.setCustomerId(CUSTOMER);
        booking.setProviderId(PROVIDER);
        booking.setListingId("it-listing");
        booking.setSlotStart(now.minus(Duration.ofHours(3)));
        booking.setSlotEnd(now.minus(Duration.ofHours(1)));
        booking.setStatus(status);
        if (pendingSince != null) {
            booking.getTimeline().add(new BookingStatusChange(
                    BookingStatus.IN_PROGRESS, BookingStatus.COMPLETION_PENDING, pendingSince, PROVIDER, null));
        }
        booking.setCreatedAt(now.minus(Duration.ofDays(3)));
        booking.setUpdatedAt(now.minus(Duration.ofDays(3)));
        return bookingRepository.save(booking);
    }

    private ServiceCompletion saveCompletion(String bookingId, Instant markedCompletedAt) {
        Instant now = Instant.now();
        ServiceCompletion completion = new ServiceCompletion();
        completion.setBookingId(bookingId);
        completion.setCustomerId(CUSTOMER);
        completion.setProviderId(PROVIDER);
        completion.setStartedAt(now.minus(Duration.ofDays(3)));
        completion.setCompletionNote(markedCompletedAt == null ? null : "Done");
        completion.setMarkedCompletedAt(markedCompletedAt);
        completion.setCreatedAt(now.minus(Duration.ofDays(3)));
        completion.setUpdatedAt(now.minus(Duration.ofDays(3)));
        return completionRepository.save(completion);
    }

    private Booking booking(String id) {
        return bookingRepository.findById(id).orElseThrow();
    }

    private ServiceCompletion completion(String bookingId) {
        return completionRepository.findByBookingId(bookingId).orElseThrow();
    }

    /** Makes the real BookingFacade method run (booking saved) and then fail, like a crash between the two writes. */
    private static Object failAfterRealCall(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
        invocation.callRealMethod();
        throw new IllegalStateException("Simulated failure after the booking was updated");
    }

    // ------------------------------------------------------------------ 1. rollback

    @Test
    @DisplayName("Start: booking rejects the transition -> no completion record is left behind")
    void startRollsBack() {
        Booking booking = saveBooking(BookingStatus.CONFIRMED, null);
        doThrow(new ResourceConflictException("BOOKING_UPDATE_CONFLICT", "simulated"))
                .when(bookingFacade).markInProgress(anyString(), anyString());
        signIn(PROVIDER, Role.PROVIDER);

        assertThrows(ApiException.class, () -> completionService.startService(booking.getId(), PROVIDER));

        assertTrue(completionRepository.findByBookingId(booking.getId()).isEmpty());
        assertEquals(BookingStatus.CONFIRMED, booking(booking.getId()).getStatus());
    }

    @Test
    @DisplayName("Mark completed: failure AFTER the booking was saved -> booking and completion both unchanged; a retry then succeeds")
    void markCompletedRollsBackBothAndRetryWorks() {
        Booking booking = saveBooking(BookingStatus.IN_PROGRESS, null);
        saveCompletion(booking.getId(), null);
        doAnswer(CompletionConsistencyIntegrationTest::failAfterRealCall)
                .when(bookingFacade).markCompletionPending(anyString(), anyString());
        signIn(PROVIDER, Role.PROVIDER);

        assertThrows(IllegalStateException.class, () -> completionService.markCompleted(booking.getId(), PROVIDER,
                new MarkCompletedRequest("Cleaned 3 rooms", List.of())));

        Booking afterFailure = booking(booking.getId());
        assertEquals(BookingStatus.IN_PROGRESS, afterFailure.getStatus());
        assertTrue(afterFailure.getTimeline().isEmpty());
        assertNull(completion(booking.getId()).getMarkedCompletedAt());
        assertNull(completion(booking.getId()).getCompletionNote());

        // The TL's example: a retry must not be blocked by half-saved evidence
        reset(bookingFacade);
        completionService.markCompleted(booking.getId(), PROVIDER, new MarkCompletedRequest("Cleaned 3 rooms", List.of()));

        assertEquals(BookingStatus.COMPLETION_PENDING, booking(booking.getId()).getStatus());
        assertNotNull(completion(booking.getId()).getMarkedCompletedAt());
        assertEquals("Cleaned 3 rooms", completion(booking.getId()).getCompletionNote());
    }

    @Test
    @DisplayName("Confirm: failure AFTER the booking was saved -> booking stays COMPLETION_PENDING, no confirmation stored")
    void confirmRollsBackBoth() {
        Booking booking = saveBooking(BookingStatus.COMPLETION_PENDING, Instant.now().minus(Duration.ofHours(1)));
        saveCompletion(booking.getId(), Instant.now().minus(Duration.ofHours(1)));
        doAnswer(CompletionConsistencyIntegrationTest::failAfterRealCall)
                .when(bookingFacade).markCompleted(anyString(), anyString());
        signIn(CUSTOMER, Role.CUSTOMER);

        assertThrows(IllegalStateException.class, () -> completionService.confirmCompletion(booking.getId(), CUSTOMER));

        assertEquals(BookingStatus.COMPLETION_PENDING, booking(booking.getId()).getStatus());
        assertNull(completion(booking.getId()).getConfirmedAt());
        assertNull(completion(booking.getId()).getConfirmationType());
    }

    @Test
    @DisplayName("Raise issue: failure AFTER the booking was saved -> booking stays COMPLETION_PENDING, no issue stored")
    void raiseIssueRollsBackBoth() {
        Booking booking = saveBooking(BookingStatus.COMPLETION_PENDING, Instant.now().minus(Duration.ofHours(1)));
        saveCompletion(booking.getId(), Instant.now().minus(Duration.ofHours(1)));
        doAnswer(CompletionConsistencyIntegrationTest::failAfterRealCall)
                .when(bookingFacade).markDisputed(anyString(), anyString(), anyString());
        signIn(CUSTOMER, Role.CUSTOMER);

        assertThrows(IllegalStateException.class, () -> completionService.raiseIssue(booking.getId(), CUSTOMER,
                new RaiseIssueRequest("The garden was not cleaned")));

        assertEquals(BookingStatus.COMPLETION_PENDING, booking(booking.getId()).getStatus());
        assertNull(completion(booking.getId()).getIssueRaisedAt());
    }

    @Test
    @DisplayName("Auto-confirm: failure AFTER the booking was saved -> booking stays COMPLETION_PENDING, no AUTO confirmation stored")
    void autoConfirmRollsBackBoth() {
        Instant markedAt = Instant.now().minus(Duration.ofHours(49));
        Booking booking = saveBooking(BookingStatus.COMPLETION_PENDING, markedAt);
        saveCompletion(booking.getId(), markedAt);
        doAnswer(CompletionConsistencyIntegrationTest::failAfterRealCall)
                                .when(bookingFacade).markCompletedBySystem(anyString());
        // The scheduled job runs outside any HTTP request (the test framework adds a mock request)
        RequestContextHolder.resetRequestAttributes();

        assertThrows(IllegalStateException.class, () -> completionService.autoConfirm(booking.getId()));

        assertEquals(BookingStatus.COMPLETION_PENDING, booking(booking.getId()).getStatus());
        assertNull(completion(booking.getId()).getConfirmedAt());
    }

    // ------------------------------------------------------------------ 2. concurrency

    private record Outcome(boolean succeeded, Throwable failure) {
    }

    private static Outcome await(Future<?> future) throws InterruptedException {
        try {
            Object result = future.get(30, TimeUnit.SECONDS);
            // autoConfirm returns false when it skipped because the customer already decided
            return new Outcome(!Boolean.FALSE.equals(result), null);
        } catch (ExecutionException ex) {
            return new Outcome(false, ex.getCause());
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new AssertionError("Request did not finish in time", ex);
        }
    }

    /** A losing request must fail with 409 and must not change anything. */
    private static void assertLoserGotConflict(Outcome loser) {
        if (loser.failure() != null) {
            ApiException api = assertInstanceOf(ApiException.class, loser.failure(),
                    () -> "Unexpected failure: " + loser.failure());
            assertEquals(HttpStatus.CONFLICT, api.getStatus());
        }
    }

    private List<Outcome> race(Callable<?> first, Callable<?> second) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> work : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return work.call();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<?> future : futures) {
                outcomes.add(await(future));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Confirm vs raise issue at the same moment (15 rounds): exactly one wins, booking always matches the completion")
    void confirmVersusRaiseIssue() throws Exception {
        int confirmations = 0;
        int issues = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Instant markedAt = Instant.now().minus(Duration.ofHours(1));
            String id = saveBooking(BookingStatus.COMPLETION_PENDING, markedAt).getId();
            saveCompletion(id, markedAt);

            List<Outcome> outcomes = race(
                    () -> {
                        signIn(CUSTOMER, Role.CUSTOMER);
                        return completionService.confirmCompletion(id, CUSTOMER);
                    },
                    () -> {
                        signIn(CUSTOMER, Role.CUSTOMER);
                        return completionService.raiseIssue(id, CUSTOMER, new RaiseIssueRequest("The garden was not cleaned"));
                    });
            Outcome confirm = outcomes.get(0);
            Outcome issue = outcomes.get(1);

            assertTrue(confirm.succeeded() ^ issue.succeeded(), "Exactly one decision must win in round " + round);
            ServiceCompletion completion = completion(id);
            Booking booking = booking(id);
            if (confirm.succeeded()) {
                assertLoserGotConflict(issue);
                assertEquals(BookingStatus.COMPLETED, booking.getStatus());
                assertEquals(CompletionConfirmationType.CUSTOMER, completion.getConfirmationType());
                assertNotNull(completion.getConfirmedAt());
                assertNull(completion.getIssueRaisedAt());
                confirmations++;
            } else {
                assertLoserGotConflict(confirm);
                assertEquals(BookingStatus.DISPUTED, booking.getStatus());
                assertNotNull(completion.getIssueRaisedAt());
                assertNull(completion.getConfirmedAt());
                assertNull(completion.getConfirmationType());
                issues++;
            }
            assertEquals(1, booking.getTimeline().size() - 1, "Exactly one booking change after COMPLETION_PENDING");
        }
        assertEquals(ROUNDS, confirmations + issues);
    }

    @Test
    @DisplayName("Customer confirm vs 48h auto-confirm at the same moment (15 rounds): exactly one confirmation, never overwritten")
    void confirmVersusAutoConfirm() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Instant markedAt = Instant.now().minus(Duration.ofHours(49));
            String id = saveBooking(BookingStatus.COMPLETION_PENDING, markedAt).getId();
            saveCompletion(id, markedAt);

            List<Outcome> outcomes = race(
                    () -> {
                        signIn(CUSTOMER, Role.CUSTOMER);
                        return completionService.confirmCompletion(id, CUSTOMER);
                    },
                    // the scheduled job runs without a logged-in user
                    () -> completionService.autoConfirm(id));
            Outcome customer = outcomes.get(0);
            Outcome auto = outcomes.get(1);

            assertTrue(customer.succeeded() ^ auto.succeeded(), "Exactly one confirmation must win in round " + round);
            ServiceCompletion completion = completion(id);
            Booking booking = booking(id);
            assertEquals(BookingStatus.COMPLETED, booking.getStatus());
            assertNotNull(completion.getConfirmedAt());
            if (customer.succeeded()) {
                assertLoserGotConflict(auto);
                assertEquals(CompletionConfirmationType.CUSTOMER, completion.getConfirmationType());
                assertEquals(CUSTOMER, booking.getTimeline().get(booking.getTimeline().size() - 1).getChangedBy());
            } else {
                assertLoserGotConflict(customer);
                assertEquals(CompletionConfirmationType.AUTO, completion.getConfirmationType());
                assertEquals(BookingStatusChange.SYSTEM, booking.getTimeline().get(booking.getTimeline().size() - 1).getChangedBy());
            }
            long completedChanges = booking.getTimeline().stream()
                    .filter(change -> change.getToStatus() == BookingStatus.COMPLETED).count();
            assertEquals(1, completedChanges, "The booking is completed exactly once");
        }
    }

    // ------------------------------------------------------------------ 3. no overwrite after a decision

    @Test
    @DisplayName("After a committed confirmation, failed requests (issue, second confirm, auto-confirm, stale update) change nothing")
    void failedRequestsCannotOverwriteCommittedDecision() {
        Instant markedAt = Instant.now().minus(Duration.ofHours(49));
        String id = saveBooking(BookingStatus.COMPLETION_PENDING, markedAt).getId();
        saveCompletion(id, markedAt);
        signIn(CUSTOMER, Role.CUSTOMER);
        completionService.confirmCompletion(id, CUSTOMER);
        ServiceCompletion committed = completion(id);
        int timelineSize = booking(id).getTimeline().size();

        ApiException issue = assertThrows(ApiException.class,
                () -> completionService.raiseIssue(id, CUSTOMER, new RaiseIssueRequest("The garden was not cleaned")));
        assertEquals(HttpStatus.CONFLICT, issue.getStatus());
        ApiException again = assertThrows(ApiException.class, () -> completionService.confirmCompletion(id, CUSTOMER));
        assertEquals(HttpStatus.CONFLICT, again.getStatus());
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        assertFalse(completionService.autoConfirm(id));

        // A request that read the record before the confirmation: its conditional updates match nothing
        Instant now = Instant.now();
        assertTrue(atomicUpdates.raiseIssue(id, now, now.minus(Duration.ofHours(72))).isEmpty());
        assertTrue(atomicUpdates.confirm(id, CompletionConfirmationType.AUTO, now, now.minus(Duration.ofHours(48))).isEmpty());

        ServiceCompletion after = completion(id);
        assertEquals(committed.getConfirmedAt(), after.getConfirmedAt());
        assertEquals(CompletionConfirmationType.CUSTOMER, after.getConfirmationType());
        assertNull(after.getIssueRaisedAt());
        assertEquals(BookingStatus.COMPLETED, booking(id).getStatus());
        assertEquals(timelineSize, booking(id).getTimeline().size());
    }
}

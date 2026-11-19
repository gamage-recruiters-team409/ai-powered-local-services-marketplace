package com.gamagerecruiters.marketplace.modules.completion.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
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
import com.gamagerecruiters.marketplace.modules.booking.domain.Booking;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatusChange;
import com.gamagerecruiters.marketplace.modules.booking.repository.BookingRepository;
import com.gamagerecruiters.marketplace.modules.completion.domain.IssueType;
import com.gamagerecruiters.marketplace.modules.completion.domain.ServiceCompletion;
import com.gamagerecruiters.marketplace.modules.completion.dto.RaiseIssueRequest;
import com.gamagerecruiters.marketplace.modules.completion.dto.ServiceCompletionResponse;
import com.gamagerecruiters.marketplace.modules.completion.repository.ServiceCompletionRepository;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionAtomicUpdates;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionDisputeLinkReconciler;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionDisputes;
import com.gamagerecruiters.marketplace.modules.completion.service.ServiceCompletionService;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;

/**
 * Real MongoDB (Testcontainers replica set): Raise Issue and the WP09 dispute link (PR #41 review, finding 2).
 *
 * <p>The WP09 side is replaced by a mock of {@link CompletionDisputes} (the case is "created" by the mock), and the
 * dispute ID write is made to fail with a spy on the real {@link CompletionAtomicUpdates}. The issue, the DISPUTED
 * booking and the reconciler run against the real database.
 */
@SpringBootTest(properties = {
        "app.completion.auto-confirm.enabled=false",
        "app.completion.dispute-link.enabled=false",
        "app.completion.dispute-link.min-age=PT0S"
})
@ActiveProfiles("integration-test")
@Tag("integration")
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "true")
class CompletionDisputeLinkIntegrationTest {

    private static final String CUSTOMER = "it-link-customer";
    private static final String PROVIDER = "it-link-provider";
    private static final RaiseIssueRequest ISSUE = new RaiseIssueRequest("The garden was not cleaned", IssueType.NOT_COMPLETED);

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
    private CompletionDisputeLinkReconciler reconciler;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private ServiceCompletionRepository completionRepository;

    @MockitoBean
    private CompletionDisputes disputes;

    @MockitoSpyBean
    private CompletionAtomicUpdates atomicUpdates;

    @BeforeEach
    void clean() {
        completionRepository.deleteAll();
        bookingRepository.deleteAll();
    }

    @AfterEach
    void clearState() {
        SecurityContextHolder.clearContext();
        reset(atomicUpdates, disputes);
    }

    // ------------------------------------------------------------------ helpers

    private static void signIn(String userId, Role role) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@example.com", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** A booking in COMPLETION_PENDING with a completion marked 1 hour ago (inside the 48-hour window). */
    private String pendingBooking() {
        Instant now = Instant.now();
        Instant markedAt = now.minus(Duration.ofHours(1));
        Booking booking = new Booking();
        booking.setId("it-" + UUID.randomUUID());
        booking.setRef("BK-IT-" + UUID.randomUUID());
        booking.setCustomerId(CUSTOMER);
        booking.setProviderId(PROVIDER);
        booking.setListingId("it-listing");
        booking.setSlotStart(now.minus(Duration.ofHours(3)));
        booking.setSlotEnd(now.minus(Duration.ofHours(1)));
        booking.setStatus(BookingStatus.COMPLETION_PENDING);
        booking.getTimeline().add(new BookingStatusChange(
                BookingStatus.IN_PROGRESS, BookingStatus.COMPLETION_PENDING, markedAt, PROVIDER, null));
        booking.setCreatedAt(now.minus(Duration.ofDays(3)));
        booking.setUpdatedAt(now.minus(Duration.ofDays(3)));
        bookingRepository.save(booking);

        ServiceCompletion completion = new ServiceCompletion();
        completion.setBookingId(booking.getId());
        completion.setCustomerId(CUSTOMER);
        completion.setProviderId(PROVIDER);
        completion.setStartedAt(now.minus(Duration.ofDays(3)));
        completion.setCompletionNote("Done");
        completion.setMarkedCompletedAt(markedAt);
        completion.setCreatedAt(now.minus(Duration.ofDays(3)));
        completion.setUpdatedAt(now.minus(Duration.ofDays(3)));
        completionRepository.save(completion);
        return booking.getId();
    }

    private ServiceCompletion completion(String bookingId) {
        return completionRepository.findByBookingId(bookingId).orElseThrow();
    }

    private BookingStatus bookingStatus(String bookingId) {
        return bookingRepository.findById(bookingId).orElseThrow().getStatus();
    }

    private void wp09Creates(String bookingId, String disputeId) {
        when(disputes.openDispute(eq(bookingId), eq(CUSTOMER), eq(Role.CUSTOMER),
                eq(DisputeCategory.SERVICE_NOT_DELIVERED), anyString())).thenReturn(disputeId);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("Success: issue, DISPUTED booking and the dispute ID are all stored")
    void raiseIssueStoresDisputeId() {
        String bookingId = pendingBooking();
        wp09Creates(bookingId, "dsp-ok");
        signIn(CUSTOMER, Role.CUSTOMER);

        ServiceCompletionResponse response = completionService.raiseIssue(bookingId, CUSTOMER, ISSUE);

        assertEquals("dsp-ok", response.disputeId());
        assertEquals("dsp-ok", completion(bookingId).getDisputeId());
        assertNotNull(completion(bookingId).getIssueRaisedAt());
        assertEquals(BookingStatus.DISPUTED, bookingStatus(bookingId));
    }

    @Test
    @DisplayName("Link write fails after the case was created: request succeeds, issue kept, reconciler stores the link, no second case")
    void linkFailureIsRecovered() {
        String bookingId = pendingBooking();
        wp09Creates(bookingId, "dsp-77");
        doThrow(new DataAccessResourceFailureException("Simulated MongoDB outage"))
                .when(atomicUpdates).recordDisputeId(eq(bookingId), eq("dsp-77"), any());
        signIn(CUSTOMER, Role.CUSTOMER);

        ServiceCompletionResponse response = completionService.raiseIssue(bookingId, CUSTOMER, ISSUE);

        // Not reported as failed; the issue and the booking change are kept
        assertEquals("dsp-77", response.disputeId());
        assertNotNull(completion(bookingId).getIssueRaisedAt());
        assertNull(completion(bookingId).getDisputeId());
        assertEquals(BookingStatus.DISPUTED, bookingStatus(bookingId));

        // A client retry is still rejected by the booking state (409), and opens no second case
        ApiException retry = assertThrows(ApiException.class, () -> completionService.raiseIssue(bookingId, CUSTOMER, ISSUE));
        assertEquals(HttpStatus.CONFLICT, retry.getStatus());

        // Database is back: the reconciler finds the existing case (read-only) and stores the link
        reset(atomicUpdates);
        when(disputes.findDisputeId(bookingId, CUSTOMER)).thenReturn(Optional.of("dsp-77"));
        assertEquals(1, reconciler.reconcileOnce());
        assertEquals("dsp-77", completion(bookingId).getDisputeId());

        // Nothing left to repair; the WP09 case was opened exactly once
        assertEquals(0, reconciler.reconcileOnce());
        verify(disputes, times(1)).openDispute(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("WP09 cannot open the case: issue still succeeds without a dispute ID; reconciler changes nothing and opens nothing")
    void wp09FailureKeepsExistingBehaviour() {
        String bookingId = pendingBooking();
        wp09Creates(bookingId, null);
        signIn(CUSTOMER, Role.CUSTOMER);

        ServiceCompletionResponse response = completionService.raiseIssue(bookingId, CUSTOMER, ISSUE);

        assertNull(response.disputeId());
        assertNotNull(completion(bookingId).getIssueRaisedAt());
        assertEquals(BookingStatus.DISPUTED, bookingStatus(bookingId));
        verify(atomicUpdates, never()).recordDisputeId(anyString(), anyString(), any());

        assertEquals(0, reconciler.reconcileOnce());
        assertNull(completion(bookingId).getDisputeId());
        verify(disputes, times(1)).openDispute(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Reconciler: one failing booking does not stop the others")
    void reconcilerContinuesAfterFailure() {
        String failing = pendingBooking();
        String healthy = pendingBooking();
        signIn(CUSTOMER, Role.CUSTOMER);
        completionService.raiseIssue(failing, CUSTOMER, ISSUE);   // mock returns null: no case, no link
        completionService.raiseIssue(healthy, CUSTOMER, ISSUE);

        when(disputes.findDisputeId(failing, CUSTOMER)).thenThrow(new IllegalStateException("Simulated"));
        when(disputes.findDisputeId(healthy, CUSTOMER)).thenReturn(Optional.of("dsp-h"));

        assertEquals(1, reconciler.reconcileOnce());
        assertEquals("dsp-h", completion(healthy).getDisputeId());
        assertNull(completion(failing).getDisputeId());
    }
}

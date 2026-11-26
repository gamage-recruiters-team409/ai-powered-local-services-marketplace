package com.gamagerecruiters.marketplace.modules.dispute.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.domain.AppealVerdict;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.dispute.domain.SettlementState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeSettlement;
import com.gamagerecruiters.marketplace.modules.dispute.domain.StatusHistoryEntry;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealDecisionRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.ResolveDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingResolutionPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeEligibilityPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeSettlementPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementInstruction;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementOutcome;
import com.gamagerecruiters.marketplace.modules.dispute.port.SettlementRejectedException;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeAdminQueryService;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeFinalizer;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeResolutionService;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeResolutionStore;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeService;
import com.gamagerecruiters.marketplace.modules.dispute.service.DisputeSettlementService;
import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;

/**
 * Decision, appeal, appeal decision, finalizer and settlement against a REAL MongoDB in a throw-away Testcontainers
 * instance (never Atlas or the shared database). Everything in the dispute module is real; the booking resolution
 * port is the real stub (spied) and the finance settlement port is mocked so outcomes can be simulated. Refunds and
 * the provider penalty go to MANUAL_REVIEW without any port call (TL decision); the port path is exercised with
 * RELEASE_TO_PROVIDER decisions. Refuses to run against a database named "marketplace".
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Tag("integration")
@ExtendWith(DockerRequiredCondition.class)
@DisabledIfSystemProperty(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@DisabledIfEnvironmentVariable(named = "SKIP_DOCKER_TESTS", matches = "(?i)true")
@TestPropertySource(properties = {
        // The test drives the finalizer itself; no background runs. Failed settlements may be retried at once.
        "app.dispute.booking-sync.enabled=false",
        "app.dispute.finalizer.enabled=false",
        "app.dispute.settlement.backoff.initial=PT0S",
        "app.dispute.settlement.backoff.max=PT0S",
        "app.dispute.settlement.max-attempts=3"
})
class DisputeResolutionMongoIntegrationTest {

    private static final String TEST_DATABASE = "dispute_resolution_it";
    private static final String FORBIDDEN_DATABASE = "marketplace";
    private static final String CUSTOMER_ID = "customer-it";
    private static final String PROVIDER_ID = "provider-it";
    private static final String ADMIN_A = "admin-a";
    private static final String ADMIN_B = "admin-b";
    private static final String NOTE = "internal note: evidence reviewed";

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
    @MockitoBean
    private DisputeSettlementPort settlementPort;
    @MockitoSpyBean
    private BookingResolutionPort bookingResolutionPort;
    /**
     * Behaves like the approved WP05 contract (BookingFacade.assertDisputeEligible): a booking that is already
     * DISPUTED and still inside its 72-hour dispute window is ELIGIBLE (alreadyDisputed = true).
     */
    @MockitoBean
    private DisputeEligibilityPort eligibilityPort;

    /**
     * WP07's real payout hold (@Primary DisputePayoutHoldAdapter) needs a captured payment, earnings ledger entries and
     * a provider balance. This test covers the dispute flow, not WP07 money movement (tested in the payment module),
     * so the hold is a no-op here, as in the dispute unit and web tests.
     */
    @MockitoBean
    private PayoutHoldPort payoutHoldPort;

    @Autowired
    private DisputeResolutionService resolutionService;
    @Autowired
    private DisputeAdminQueryService adminQueryService;
    @Autowired
    private DisputeService disputeService;
    @Autowired
    private DisputeFinalizer finalizer;
    @Autowired
    private DisputeResolutionStore store;
    @Autowired
    private DisputeSettlementService settlementService;
    @Autowired
    private MongoTemplate mongoTemplate;

    private int bookingCounter;

    @BeforeEach
    void refuseSharedDatabaseAndClean() {
        String dbName = mongoTemplate.getDb().getName();
        if (FORBIDDEN_DATABASE.equals(dbName)) {
            throw new IllegalStateException("Refusing to run: connected to the database named \"" + FORBIDDEN_DATABASE
                    + "\". This test deletes disputes and must only run against a throw-away database.");
        }
        assertThat(dbName).isEqualTo(TEST_DATABASE);
        mongoTemplate.remove(new Query(), Dispute.class);
        doReturn(SettlementOutcome.NOT_APPLIED).when(settlementPort).settle(any());
    }

    // ---------- helpers ----------

    /** A visible dispute in {@code status}, raised by the customer, response window already passed. */
    private Dispute insert(DisputeStatus status) {
        Instant created = Instant.now().minus(Duration.ofDays(4));
        Dispute dispute = new Dispute();
        dispute.setBookingId("booking-it-" + (++bookingCounter));
        dispute.setCustomerId(CUSTOMER_ID);
        dispute.setProviderId(PROVIDER_ID);
        dispute.setRaisedBy(CUSTOMER_ID);
        dispute.setRaisedByRole(Role.CUSTOMER);
        dispute.setCategory(DisputeCategory.POOR_QUALITY);
        dispute.setDescription("Integration test dispute");
        dispute.setCreatedAt(created);
        dispute.setResponseDueAt(created.plus(DisputeRules.RESPONSE_WINDOW));
        dispute.setBookingSyncState(BookingSyncState.SYNCED);
        dispute.changeStatus(DisputeStatus.AWAITING_RESPONSE, CUSTOMER_ID, Role.CUSTOMER, null, created);
        if (status != DisputeStatus.AWAITING_RESPONSE) {
            dispute.changeStatus(status, PROVIDER_ID, Role.PROVIDER, null, created.plusSeconds(60));
        }
        return mongoTemplate.insert(dispute);
    }

    private Dispute load(String id) {
        return mongoTemplate.findById(id, Dispute.class);
    }

    private static ResolveDisputeRequest refundPartial(int percent, boolean penalty) {
        return new ResolveDisputeRequest(ResolutionAction.REFUND_PARTIAL, percent, penalty, "  " + NOTE + "  ");
    }

    private static ResolveDisputeRequest release() {
        return new ResolveDisputeRequest(ResolutionAction.RELEASE_TO_PROVIDER, null, false, NOTE);
    }

    /** RELEASE_TO_PROVIDER by admin A, appeal window already passed (the finalizer makes it final). */
    private String releaseReadyToFinalize() {
        String id = insert(DisputeStatus.UNDER_REVIEW).getId();
        resolutionService.resolve(ADMIN_A, Role.ADMIN, id, release());
        expireAppealWindow(id);
        return id;
    }

    private DisputeSettlement settlementOf(String id) {
        return load(id).getSettlement();
    }

    private String resolvedByAdminA() {
        String id = insert(DisputeStatus.UNDER_REVIEW).getId();
        resolutionService.resolve(ADMIN_A, Role.ADMIN, id, refundPartial(40, true));
        return id;
    }

    private String appealedByCustomer() {
        String id = resolvedByAdminA();
        resolutionService.appeal(CUSTOMER_ID, Role.CUSTOMER, id, new AppealDisputeRequest("The refund is too low", List.of()));
        return id;
    }

    private void expireAppealWindow(String id) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("appealDeadline", Instant.now().minusSeconds(60)), Dispute.class);
    }

    private static void assertApiError(Throwable thrown, int status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus().value()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    private static long entriesTo(Dispute dispute, DisputeStatus status) {
        return dispute.getStatusHistory().stream().filter(e -> e.getToStatus() == status).count();
    }

    /** Runs the tasks at the same moment; returns each result or the ApiException it threw. */
    private static List<Object> simultaneously(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (ApiException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- admin decision ----------

    @Test
    @DisplayName("decision: RESOLVED with the decision, a 48h appeal window and one history entry; the note is trimmed")
    void resolveStoresDecision() {
        String id = insert(DisputeStatus.UNDER_REVIEW).getId();

        AdminDisputeDetailResponse result = resolutionService.resolve(ADMIN_A, Role.ADMIN, id, refundPartial(40, true));

        Dispute stored = load(id);
        assertThat(stored.getStatus()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(stored.getActiveBookingKey()).as("still blocks a new dispute").isEqualTo(stored.getBookingId());
        assertThat(stored.getDecision().getAction()).isEqualTo(ResolutionAction.REFUND_PARTIAL);
        assertThat(stored.getDecision().getCustomerRefundPercent()).isEqualTo(40);
        assertThat(stored.getDecision().getProviderPenaltyPercent()).isEqualTo(DisputeRules.PROVIDER_PENALTY_PERCENT);
        assertThat(stored.getDecision().getDecidedBy()).isEqualTo(ADMIN_A);
        assertThat(stored.getDecision().getNote()).isEqualTo(NOTE);
        assertThat(stored.getAppealDeadline())
                .isEqualTo(stored.getDecision().getDecidedAt().plus(DisputeRules.APPEAL_WINDOW));
        StatusHistoryEntry last = stored.getStatusHistory().get(stored.getStatusHistory().size() - 1);
        assertThat(last.getFromStatus()).isEqualTo(DisputeStatus.UNDER_REVIEW);
        assertThat(last.getToStatus()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(last.getActorId()).isEqualTo(ADMIN_A);
        assertThat(last.getActorRole()).isEqualTo(Role.ADMIN);
        assertThat(result.appealStatus()).isEqualTo(AppealStatus.OPEN);
    }

    @Test
    @DisplayName("decision: two admins resolve at the same moment -> exactly one succeeds, the other gets 409")
    void concurrentResolveOneWins() throws Exception {
        String id = insert(DisputeStatus.UNDER_REVIEW).getId();

        List<Object> outcomes = simultaneously(List.of(
                () -> resolutionService.resolve(ADMIN_A, Role.ADMIN, id, refundPartial(40, false)),
                () -> resolutionService.resolve(ADMIN_B, Role.ADMIN, id,
                        new ResolveDisputeRequest(ResolutionAction.RELEASE_TO_PROVIDER, null, false, NOTE))));

        assertThat(outcomes).filteredOn(AdminDisputeDetailResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ApiException.class::isInstance).singleElement()
                .satisfies(e -> assertApiError((Throwable) e, 409, "INVALID_DISPUTE_STATUS"));
        assertThat(entriesTo(load(id), DisputeStatus.RESOLVED)).isEqualTo(1);
    }

    @Test
    @DisplayName("decision: AWAITING_RESPONSE inside the response window -> 409; after it -> allowed")
    void awaitingResponseOnlyAfterWindow() {
        String id = insert(DisputeStatus.AWAITING_RESPONSE).getId();
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("responseDueAt", Instant.now().plus(Duration.ofHours(1))), Dispute.class);

        assertApiError(catchThrowable(() -> resolutionService.resolve(ADMIN_A, Role.ADMIN, id, refundPartial(40, false))),
                409, "INVALID_DISPUTE_STATUS");
        assertThat(load(id).getStatus()).isEqualTo(DisputeStatus.AWAITING_RESPONSE);

        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("responseDueAt", Instant.now().minusSeconds(1)), Dispute.class);
        resolutionService.resolve(ADMIN_A, Role.ADMIN, id, refundPartial(40, false));
        assertThat(load(id).getStatus()).isEqualTo(DisputeStatus.RESOLVED);
    }

    // ---------- appeal ----------

    @Test
    @DisplayName("appeal: customer and provider appeal at the same moment -> one wins, the other gets 409 "
            + "APPEAL_ALREADY_FILED")
    void concurrentAppealsOneWins() throws Exception {
        String id = resolvedByAdminA();

        List<Object> outcomes = simultaneously(List.of(
                () -> resolutionService.appeal(CUSTOMER_ID, Role.CUSTOMER, id, new AppealDisputeRequest("Too low", List.of())),
                () -> resolutionService.appeal(PROVIDER_ID, Role.PROVIDER, id, new AppealDisputeRequest("Too high", List.of()))));

        assertThat(outcomes).filteredOn(DisputeDetailResponse.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ApiException.class::isInstance).singleElement()
                .satisfies(e -> assertApiError((Throwable) e, 409, "APPEAL_ALREADY_FILED"));
        Dispute stored = load(id);
        assertThat(stored.getStatus()).isEqualTo(DisputeStatus.APPEALED);
        assertThat(entriesTo(stored, DisputeStatus.APPEALED)).isEqualTo(1);
        assertThat(stored.getAppeal().getReviewDueAt())
                .as("default review SLA 96h").isEqualTo(stored.getAppeal().getFiledAt().plus(Duration.ofHours(96)));
    }

    @Test
    @DisplayName("appeal: a second appeal -> 409 APPEAL_ALREADY_FILED, the first one is unchanged")
    void secondAppealRejected() {
        String id = appealedByCustomer();
        Dispute before = load(id);

        assertApiError(catchThrowable(() -> resolutionService.appeal(PROVIDER_ID, Role.PROVIDER, id,
                new AppealDisputeRequest("Me too", List.of()))), 409, "APPEAL_ALREADY_FILED");
        assertThat(load(id).getVersion()).isEqualTo(before.getVersion());
    }

    @Test
    @DisplayName("appeal: after the 48h window -> 409 APPEAL_WINDOW_CLOSED, nothing changed")
    void appealAfterWindowRejected() {
        String id = resolvedByAdminA();
        expireAppealWindow(id);
        Dispute before = load(id);

        assertApiError(catchThrowable(() -> resolutionService.appeal(CUSTOMER_ID, Role.CUSTOMER, id,
                new AppealDisputeRequest("Late", List.of()))), 409, "APPEAL_WINDOW_CLOSED");
        Dispute after = load(id);
        assertThat(after.getStatus()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(after.getAppeal()).isNull();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
    }

    @Test
    @DisplayName("appeal: someone who is not a party -> 403 NOT_DISPUTE_PARTY")
    void nonPartyCannotAppeal() {
        String id = resolvedByAdminA();

        assertApiError(catchThrowable(() -> resolutionService.appeal("stranger", Role.CUSTOMER, id,
                new AppealDisputeRequest("x", List.of()))), 403, "NOT_DISPUTE_PARTY");
    }

    // ---------- appeal decision ----------

    @Test
    @DisplayName("appeal decision: the admin who made the decision -> 403 APPEAL_REVIEWER_MUST_DIFFER, nothing changed")
    void sameAdminCannotDecideOwnAppeal() {
        String id = appealedByCustomer();
        Dispute before = load(id);

        assertApiError(catchThrowable(() -> resolutionService.decideAppeal(ADMIN_A, Role.ADMIN, id,
                new AppealDecisionRequest(AppealVerdict.UPHOLD, null, null, null, NOTE))), 403, "APPEAL_REVIEWER_MUST_DIFFER");

        Dispute after = load(id);
        assertThat(after.getStatus()).isEqualTo(DisputeStatus.APPEALED);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getAppeal().getVerdict()).isNull();
        verify(settlementPort, never()).settle(any());
    }

    @Test
    @DisplayName("appeal decision CHANGE by a different admin -> RESOLVED_FINAL with the new binding decision; the "
            + "refund goes to MANUAL_REVIEW REFUND_PENDING_INTEGRATION without calling any port")
    void differentAdminChangesDecision() {
        String id = appealedByCustomer();

        AdminDisputeDetailResponse result = resolutionService.decideAppeal(ADMIN_B, Role.ADMIN, id,
                new AppealDecisionRequest(AppealVerdict.CHANGE, ResolutionAction.REFUND_FULL, null, false, NOTE));

        Dispute stored = load(id);
        assertThat(stored.getStatus()).isEqualTo(DisputeStatus.RESOLVED_FINAL);
        assertThat(stored.getActiveBookingKey()).as("kept: the stub did not resolve the booking")
                .isEqualTo(stored.getBookingId());
        assertThat(stored.getDecision().getAction()).as("original kept").isEqualTo(ResolutionAction.REFUND_PARTIAL);
        assertThat(stored.getFinalDecision().getAction()).isEqualTo(ResolutionAction.REFUND_FULL);
        assertThat(stored.getFinalDecision().getCustomerRefundPercent()).isEqualTo(100);
        assertThat(stored.getFinalDecision().getDecidedBy()).isEqualTo(ADMIN_B);
        assertThat(stored.getAppeal().getVerdict()).isEqualTo(AppealVerdict.CHANGE);
        assertThat(stored.getAppeal().getReviewedBy()).isEqualTo(ADMIN_B);
        assertThat(stored.getSettlement().getState()).isEqualTo(SettlementState.MANUAL_REVIEW);
        assertThat(stored.getSettlement().getManualReviewReason()).isEqualTo("REFUND_PENDING_INTEGRATION");
        assertThat(stored.getSettlement().getAttempts()).isZero();
        assertThat(result.appealStatus()).isEqualTo(AppealStatus.CHANGED);
        assertThat(result.settlement().state()).isEqualTo(SettlementState.MANUAL_REVIEW);
        verify(bookingResolutionPort, never()).resolveBooking(any());
        verify(settlementPort, never()).settle(any());

        // A finalizer run afterwards does not pick it up: MANUAL_REVIEW is never retried automatically.
        assertThat(finalizer.run().settled()).isZero();
        verify(settlementPort, never()).settle(any());

        // Final and binding: no second appeal and no second decision.
        assertApiError(catchThrowable(() -> resolutionService.appeal(PROVIDER_ID, Role.PROVIDER, id,
                new AppealDisputeRequest("again", List.of()))), 409, "APPEAL_ALREADY_FILED");
        assertApiError(catchThrowable(() -> resolutionService.decideAppeal(ADMIN_B, Role.ADMIN, id,
                new AppealDecisionRequest(AppealVerdict.UPHOLD, null, null, null, NOTE))), 409, "INVALID_DISPUTE_STATUS");
    }

    // ---------- finalizer and settlement ----------

    @Test
    @DisplayName("finalizer: two instances at the same moment finalize each expired decision exactly once; a later "
            + "run changes nothing (idempotent)")
    void finalizerIsIdempotentAcrossInstances() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(releaseReadyToFinalize());
        }
        String stillOpen = resolvedByAdminA();
        DisputeFinalizer second = new DisputeFinalizer(store, settlementService, true, 50);

        List<Object> results = simultaneously(List.of(() -> finalizer.run(), () -> second.run()));

        int finalized = results.stream().mapToInt(r -> ((DisputeFinalizer.Result) r).finalized()).sum();
        assertThat(finalized).isEqualTo(6);
        for (String id : ids) {
            Dispute stored = load(id);
            assertThat(stored.getStatus()).isEqualTo(DisputeStatus.RESOLVED_FINAL);
            assertThat(entriesTo(stored, DisputeStatus.RESOLVED_FINAL)).isEqualTo(1);
            assertThat(stored.getFinalDecision().getAction()).isEqualTo(stored.getDecision().getAction());
            assertThat(stored.getSettlement().getAttempts()).as("NOT_APPLIED is not a failed attempt").isZero();
            assertThat(stored.getSettlement().getState()).isEqualTo(SettlementState.NOT_APPLIED);
            assertThat(stored.getSettlement().getNotAppliedReason()).isEqualTo("WP07_NOT_INTEGRATED");
        }
        assertThat(load(stillOpen).getStatus()).as("window still open").isEqualTo(DisputeStatus.RESOLVED);
        verify(settlementPort, times(6)).settle(any());

        assertThat(finalizer.run()).isEqualTo(new DisputeFinalizer.Result(0, 0));
        verify(settlementPort, times(6)).settle(any());
    }

    @Test
    @DisplayName("settlement: the finance port fails or times out -> decision and RESOLVED_FINAL kept, FAILED, booking "
            + "NOT called; when finance recovers it is APPLIED first, then the booking step runs; never reverted")
    void failingSettlementNeverRevertsDecision() {
        doThrow(new QueryTimeoutException("payout service timed out")).when(settlementPort).settle(any());
        String id = insert(DisputeStatus.UNDER_REVIEW).getId();
        resolutionService.resolve(ADMIN_A, Role.ADMIN, id, release());
        resolutionService.appeal(CUSTOMER_ID, Role.CUSTOMER, id, new AppealDisputeRequest("Please review", List.of()));

        resolutionService.decideAppeal(ADMIN_B, Role.ADMIN, id,
                new AppealDecisionRequest(AppealVerdict.UPHOLD, null, null, null, NOTE));

        Dispute afterFailure = load(id);
        assertThat(afterFailure.getStatus()).isEqualTo(DisputeStatus.RESOLVED_FINAL);
        assertThat(afterFailure.getFinalDecision().getAction()).isEqualTo(ResolutionAction.RELEASE_TO_PROVIDER);
        assertThat(afterFailure.getSettlement().getState()).isEqualTo(SettlementState.FAILED);
        assertThat(afterFailure.getSettlement().getFinanceState()).isEqualTo(SettlementState.FAILED);
        assertThat(afterFailure.getSettlement().getBookingState()).isEqualTo(SettlementState.PENDING);
        assertThat(afterFailure.getSettlement().getLastErrorCode()).isEqualTo("QueryTimeoutException");
        assertThat(afterFailure.getSettlement().getAttempts()).isEqualTo(1);
        verify(bookingResolutionPort, never()).resolveBooking(any());

        // The finance module recovers and confirms on the retry; only then is the booking step called.
        doReturn(SettlementOutcome.APPLIED).when(settlementPort).settle(any());
        assertThat(finalizer.run().settled()).isEqualTo(1);

        Dispute afterRetry = load(id);
        assertThat(afterRetry.getStatus()).isEqualTo(DisputeStatus.RESOLVED_FINAL);
        assertThat(afterRetry.getFinalDecision().getAction()).isEqualTo(ResolutionAction.RELEASE_TO_PROVIDER);
        assertThat(afterRetry.getSettlement().getFinanceState()).isEqualTo(SettlementState.APPLIED);
        assertThat(afterRetry.getSettlement().getBookingState()).as("booking stub: nothing applied")
                .isEqualTo(SettlementState.NOT_APPLIED);
        assertThat(afterRetry.getSettlement().getState()).isEqualTo(SettlementState.NOT_APPLIED);
        assertThat(afterRetry.getSettlement().getNotAppliedReason()).isEqualTo("WP05_NOT_INTEGRATED");
        assertThat(afterRetry.getSettlement().getAttempts()).as("only the failure counted").isEqualTo(1);
        verify(bookingResolutionPort, times(1)).resolveBooking(any());
        verify(settlementPort, times(2)).settle(any());
    }

    // ---------- duplicate-dispute protection after finalization ----------

    /** What the real eligibility check answers for a booking that is DISPUTED and inside its 72h window. */
    private void bookingIsDisputedAndEligible(String bookingId) {
        when(eligibilityPort.assertEligible(bookingId))
                .thenReturn(new BookingSnapshot(bookingId, CUSTOMER_ID, PROVIDER_ID, true));
    }

    private static RaiseDisputeRequest raiseRequest(String bookingId) {
        return new RaiseDisputeRequest(bookingId, DisputeCategory.NO_SHOW, "A second dispute for the same booking",
                List.of());
    }

    private List<Dispute> disputesFor(String bookingId) {
        return mongoTemplate.find(Query.query(Criteria.where("bookingId").is(bookingId)), Dispute.class);
    }

    @Test
    @DisplayName("finalized (window passed) while booking resolution is NOT_APPLIED: the booking stays DISPUTED and "
            + "keeps its key, so a second dispute inside the 72h window is rejected with 409, nothing created")
    void finalizedNotAppliedBlocksSecondDispute() {
        String id = resolvedByAdminA();
        expireAppealWindow(id);
        String bookingId = load(id).getBookingId();

        finalizer.run();

        Dispute finalDispute = load(id);
        assertThat(finalDispute.getStatus()).isEqualTo(DisputeStatus.RESOLVED_FINAL);
        assertThat(finalDispute.getSettlement().getBookingState()).isNotEqualTo(SettlementState.APPLIED);
        assertThat(finalDispute.getActiveBookingKey()).isEqualTo(bookingId);
        // Nothing moved the booking out of DISPUTED (the stub never calls the Booking module).
        verifyNoInteractions(bookingFacade);

        bookingIsDisputedAndEligible(bookingId);
        assertApiError(catchThrowable(() -> disputeService.raiseDispute(CUSTOMER_ID, Role.CUSTOMER,
                raiseRequest(bookingId))), 409, "DISPUTE_ALREADY_EXISTS");
        assertApiError(catchThrowable(() -> disputeService.raiseDispute(PROVIDER_ID, Role.PROVIDER,
                raiseRequest(bookingId))), 409, "DISPUTE_ALREADY_EXISTS");

        assertThat(disputesFor(bookingId)).singleElement().satisfies(d -> assertThat(d.getId()).isEqualTo(id));
        verifyNoInteractions(bookingFacade);
    }

    @Test
    @DisplayName("finalized by the appeal decision while NOT_APPLIED: a second dispute is rejected the same way")
    void appealDecisionNotAppliedBlocksSecondDispute() {
        String id = appealedByCustomer();
        String bookingId = load(id).getBookingId();
        resolutionService.decideAppeal(ADMIN_B, Role.ADMIN, id,
                new AppealDecisionRequest(AppealVerdict.UPHOLD, null, null, null, NOTE));
        assertThat(load(id).getActiveBookingKey()).isEqualTo(bookingId);

        bookingIsDisputedAndEligible(bookingId);
        assertApiError(catchThrowable(() -> disputeService.raiseDispute(CUSTOMER_ID, Role.CUSTOMER,
                raiseRequest(bookingId))), 409, "DISPUTE_ALREADY_EXISTS");
        assertThat(disputesFor(bookingId)).hasSize(1);
    }

    @Test
    @DisplayName("booking resolution confirmed APPLIED -> only then is the booking's dispute key released")
    void appliedBookingResolutionReleasesKey() {
        doReturn(SettlementOutcome.APPLIED).when(settlementPort).settle(any());
        doReturn(SettlementOutcome.APPLIED).when(bookingResolutionPort).resolveBooking(any());
        String id = releaseReadyToFinalize();

        finalizer.run();

        Dispute stored = load(id);
        assertThat(stored.getStatus()).isEqualTo(DisputeStatus.RESOLVED_FINAL);
        assertThat(stored.getSettlement().getState()).isEqualTo(SettlementState.APPLIED);
        assertThat(stored.getSettlement().getBookingState()).isEqualTo(SettlementState.APPLIED);
        assertThat(stored.getActiveBookingKey()).isNull();
        // One dispute per booking still holds after the key is released.
        bookingIsDisputedAndEligible(stored.getBookingId());
        assertApiError(catchThrowable(() -> disputeService.raiseDispute(CUSTOMER_ID, Role.CUSTOMER,
                raiseRequest(stored.getBookingId()))), 409, "DISPUTE_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("recovery still works: the user's own unfinished (PENDING) dispute on a DISPUTED booking is "
            + "completed by a retry, not rejected")
    void ownPendingIntentRecoveryStillWorks() {
        Dispute intent = insert(DisputeStatus.AWAITING_RESPONSE);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(intent.getId())),
                new Update().set("bookingSyncState", BookingSyncState.PENDING), Dispute.class);
        bookingIsDisputedAndEligible(intent.getBookingId());

        DisputeDetailResponse recovered = disputeService.raiseDispute(CUSTOMER_ID, Role.CUSTOMER,
                raiseRequest(intent.getBookingId()));

        assertThat(recovered.id()).isEqualTo(intent.getId());
        assertThat(disputesFor(intent.getBookingId())).singleElement()
                .satisfies(d -> assertThat(d.getBookingSyncState()).isEqualTo(BookingSyncState.SYNCED));
    }

    // ---------- party and admin views ----------

    @Test
    @DisplayName("party view: outcome and appeal state without the admin's identity or note; penalty only for the "
            + "provider")
    void partyViewHidesAdminData() {
        String id = resolvedByAdminA();

        DisputeDetailResponse customer = disputeService.getDisputeForParty(CUSTOMER_ID, id);
        DisputeDetailResponse provider = disputeService.getDisputeForParty(PROVIDER_ID, id);

        assertThat(customer.outcome().action()).isEqualTo(ResolutionAction.REFUND_PARTIAL);
        assertThat(customer.outcome().customerRefundPercent()).isEqualTo(40);
        assertThat(customer.outcome().providerPenaltyApplied()).isNull();
        assertThat(provider.outcome().providerPenaltyApplied()).isTrue();
        assertThat(customer.appealEligible()).isTrue();
        assertThat(customer.appealStatus()).isEqualTo(AppealStatus.OPEN);
        assertThat(customer.statusHistory()).allSatisfy(entry -> {
            assertThat(entry.actorId()).isNotEqualTo(ADMIN_A);
            assertThat(String.valueOf(entry.reason())).doesNotContain(NOTE);
        });
        assertThat(customer.toString()).doesNotContain(ADMIN_A).doesNotContain(NOTE);
    }

    @Test
    @DisplayName("admin list and summary: filters, search by id prefix, PENDING sync records hidden")
    void adminListAndSummary() {
        String underReview = insert(DisputeStatus.UNDER_REVIEW).getId();
        String resolved = resolvedByAdminA();
        String appealed = appealedByCustomer();
        Dispute pending = insert(DisputeStatus.UNDER_REVIEW);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(pending.getId())),
                new Update().set("bookingSyncState", BookingSyncState.PENDING), Dispute.class);

        assertThat(adminQueryService.search(null, null, null, null, null, null, 0, 20).totalElements()).isEqualTo(3);
        assertThat(adminQueryService.search("UNDER_REVIEW", null, null, null, null, null, 0, 20).content())
                .singleElement().satisfies(d -> assertThat(d.id()).isEqualTo(underReview));
        assertThat(adminQueryService.search(null, "POOR_QUALITY", load(appealed).getBookingId(), null, null,
                "createdAt,asc", 0, 20).content()).singleElement().satisfies(d -> assertThat(d.id()).isEqualTo(appealed));
        assertThat(adminQueryService.search(null, null, resolved, null, null, null, 0, 20).content())
                .singleElement().satisfies(d -> assertThat(d.id()).isEqualTo(resolved));

        var summary = adminQueryService.summary();
        assertThat(summary.underReview()).as("PENDING not counted").isEqualTo(1);
        assertThat(summary.appealed()).isEqualTo(1);
        assertThat(summary.resolvedLast30Days()).isEqualTo(2);
        assertThat(summary.manualReview()).isZero();
    }

    @Test
    @DisplayName("admin summary counts MANUAL_REVIEW and REJECTED settlements; the list and detail show the state and "
            + "reason read-only; the party view only gets the neutral payment note")
    void adminSeesSettlementStateParticipantsDoNot() {
        String refund = resolvedByAdminA();
        expireAppealWindow(refund);
        String rejected = releaseReadyToFinalize();
        doThrow(new SettlementRejectedException("NO_CAPTURED_PAYMENT", "no payment")).when(settlementPort).settle(any());
        finalizer.run();

        assertThat(settlementOf(refund).getState()).isEqualTo(SettlementState.MANUAL_REVIEW);
        assertThat(settlementOf(rejected).getState()).isEqualTo(SettlementState.REJECTED);
        assertThat(adminQueryService.summary().manualReview()).isEqualTo(2);
        assertThat(adminQueryService.search(null, null, rejected, null, null, null, 0, 20).content()).singleElement()
                .satisfies(row -> {
                    assertThat(row.settlementState()).isEqualTo(SettlementState.REJECTED);
                    assertThat(row.manualReviewReason()).isEqualTo("SETTLEMENT_REJECTED:NO_CAPTURED_PAYMENT");
                });
        AdminDisputeDetailResponse detail = adminQueryService.getDetail(refund);
        assertThat(detail.settlement().state()).isEqualTo(SettlementState.MANUAL_REVIEW);
        assertThat(detail.settlement().manualReviewReason()).isEqualTo("REFUND_PENDING_INTEGRATION");

        DisputeDetailResponse party = disputeService.getDisputeForParty(CUSTOMER_ID, rejected);
        assertThat(party.outcome().paymentNote()).isEqualTo(
                "Payment handling for this dispute requires assistance. Our support team is reviewing this case.");
        assertThat(party.toString()).doesNotContain("NO_CAPTURED_PAYMENT")
                .doesNotContain("settlement");
    }

    // ---------- settlement order and outcomes ----------

    @Test
    @DisplayName("RELEASE_TO_PROVIDER + finance APPLIED -> the booking port is called AFTER the settlement port, never "
            + "before; same idempotency key; APPLIED")
    void bookingStepOnlyAfterFinanceApplied() {
        doReturn(SettlementOutcome.APPLIED).when(settlementPort).settle(any());
        doReturn(SettlementOutcome.APPLIED).when(bookingResolutionPort).resolveBooking(any());
        String id = releaseReadyToFinalize();

        finalizer.run();

        InOrder order = inOrder(settlementPort, bookingResolutionPort);
        ArgumentCaptor<SettlementInstruction> finance = ArgumentCaptor.forClass(SettlementInstruction.class);
        ArgumentCaptor<SettlementInstruction> booking = ArgumentCaptor.forClass(SettlementInstruction.class);
        order.verify(settlementPort).settle(finance.capture());
        order.verify(bookingResolutionPort).resolveBooking(booking.capture());
        assertThat(finance.getValue().idempotencyKey()).isEqualTo("dispute-" + id);
        assertThat(booking.getValue().idempotencyKey()).isEqualTo("dispute-" + id);
        assertThat(settlementOf(id).getState()).isEqualTo(SettlementState.APPLIED);
        assertThat(settlementOf(id).getFinanceState()).isEqualTo(SettlementState.APPLIED);
    }

    @Test
    @DisplayName("finance runtime exception -> counted, booking NOT called; after max-attempts (3) -> MANUAL_REVIEW "
            + "RETRY_LIMIT_REACHED and never claimed again")
    void retryLimitLeadsToManualReview() {
        doThrow(new IllegalStateException("finance down")).when(settlementPort).settle(any());
        String id = releaseReadyToFinalize();

        finalizer.run();
        assertThat(settlementOf(id).getState()).isEqualTo(SettlementState.FAILED);
        assertThat(settlementOf(id).getAttempts()).isEqualTo(1);
        finalizer.run();
        finalizer.run();

        DisputeSettlement settlement = settlementOf(id);
        assertThat(settlement.getState()).isEqualTo(SettlementState.MANUAL_REVIEW);
        assertThat(settlement.getManualReviewReason()).isEqualTo("RETRY_LIMIT_REACHED");
        assertThat(settlement.getAttempts()).isEqualTo(3);
        assertThat(settlement.getLastErrorCode()).isEqualTo("IllegalStateException");
        assertThat(finalizer.run().settled()).isZero();
        verify(settlementPort, times(3)).settle(any());
        verify(bookingResolutionPort, never()).resolveBooking(any());
        assertThat(load(id).getStatus()).isEqualTo(DisputeStatus.RESOLVED_FINAL);
    }

    @Test
    @DisplayName("SettlementRejectedException -> REJECTED with the reason code stored; the next recovery run does not "
            + "retry it; booking never called")
    void permanentRejectionIsTerminal() {
        doThrow(new SettlementRejectedException("NO_CAPTURED_PAYMENT", "no payment")).when(settlementPort).settle(any());
        String id = releaseReadyToFinalize();

        finalizer.run();
        assertThat(finalizer.run().settled()).as("not retried").isZero();

        DisputeSettlement settlement = settlementOf(id);
        assertThat(settlement.getState()).isEqualTo(SettlementState.REJECTED);
        assertThat(settlement.getFinanceState()).isEqualTo(SettlementState.REJECTED);
        assertThat(settlement.getManualReviewReason()).isEqualTo("SETTLEMENT_REJECTED:NO_CAPTURED_PAYMENT");
        assertThat(settlement.getAttempts()).isZero();
        verify(settlementPort, times(1)).settle(any());
        verify(bookingResolutionPort, never()).resolveBooking(any());
        assertThat(load(id).getActiveBookingKey()).as("booking still DISPUTED: key kept").isNotNull();
    }

    @Test
    @DisplayName("finance APPLIED + booking exception -> the retry calls ONLY the booking port, never settlement again")
    void retryRepeatsOnlyBookingStep() {
        doReturn(SettlementOutcome.APPLIED).when(settlementPort).settle(any());
        doThrow(new IllegalStateException("booking down")).when(bookingResolutionPort).resolveBooking(any());
        String id = releaseReadyToFinalize();

        finalizer.run();
        DisputeSettlement afterFailure = settlementOf(id);
        assertThat(afterFailure.getState()).isEqualTo(SettlementState.FAILED);
        assertThat(afterFailure.getFinanceState()).isEqualTo(SettlementState.APPLIED);
        assertThat(afterFailure.getBookingState()).isEqualTo(SettlementState.FAILED);

        doReturn(SettlementOutcome.APPLIED).when(bookingResolutionPort).resolveBooking(any());
        finalizer.run();

        assertThat(settlementOf(id).getState()).isEqualTo(SettlementState.APPLIED);
        verify(settlementPort, times(1)).settle(any());
        verify(bookingResolutionPort, times(2)).resolveBooking(any());
    }

    @Test
    @DisplayName("REFUND_FULL, REFUND_PARTIAL and the penalty -> MANUAL_REVIEW REFUND_PENDING_INTEGRATION, the "
            + "settlement port is never called; the decision stays final")
    void refundsAndPenaltyGoToManualReview() {
        List<String> ids = new ArrayList<>();
        for (ResolveDisputeRequest request : List.of(
                new ResolveDisputeRequest(ResolutionAction.REFUND_FULL, null, false, NOTE),
                new ResolveDisputeRequest(ResolutionAction.REFUND_PARTIAL, 30, false, NOTE),
                new ResolveDisputeRequest(ResolutionAction.REFUND_PARTIAL, 60, true, NOTE))) {
            String id = insert(DisputeStatus.UNDER_REVIEW).getId();
            resolutionService.resolve(ADMIN_A, Role.ADMIN, id, request);
            expireAppealWindow(id);
            ids.add(id);
        }

        finalizer.run();
        finalizer.run();

        for (String id : ids) {
            Dispute stored = load(id);
            assertThat(stored.getStatus()).isEqualTo(DisputeStatus.RESOLVED_FINAL);
            assertThat(stored.getSettlement().getState()).isEqualTo(SettlementState.MANUAL_REVIEW);
            assertThat(stored.getSettlement().getManualReviewReason()).isEqualTo("REFUND_PENDING_INTEGRATION");
            assertThat(stored.getSettlement().getAttempts()).isZero();
        }
        verify(settlementPort, never()).settle(any());
        verify(bookingResolutionPort, never()).resolveBooking(any());
    }

    @Test
    @DisplayName("NOT_APPLIED (WP07 not connected) -> not counted as a failure; re-checked only after the normal "
            + "interval")
    void notAppliedIsNotAFailure() {
        String id = releaseReadyToFinalize();

        finalizer.run();
        assertThat(finalizer.run().settled()).as("not re-checked before the interval").isZero();

        DisputeSettlement settlement = settlementOf(id);
        assertThat(settlement.getState()).isEqualTo(SettlementState.NOT_APPLIED);
        assertThat(settlement.getAttempts()).isZero();
        assertThat(settlement.getLastErrorCode()).isNull();
        assertThat(settlement.getNotAppliedReason()).isEqualTo("WP07_NOT_INTEGRATED");

        // After the re-check interval (default 1h) the recovery job looks at it again.
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("settlement.lastAttemptAt", Instant.now().minus(Duration.ofHours(2))), Dispute.class);
        assertThat(finalizer.run().settled()).isEqualTo(1);
        assertThat(settlementOf(id).getAttempts()).isZero();
        verify(settlementPort, times(2)).settle(any());
    }

    @Test
    @DisplayName("two finalizer runs at the same moment -> only one works on the dispute (lease); the port is called once")
    void leaseAllowsOnlyOneWorker() throws Exception {
        String id = releaseReadyToFinalize();
        // Finalize first, so both runs compete only for the settlement.
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update()
                .set("status", DisputeStatus.RESOLVED_FINAL)
                .set("finalDecision", load(id).getDecision())
                .set("settlement", DisputeSettlement.pending()), Dispute.class);
        CountDownLatch insidePort = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            insidePort.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return SettlementOutcome.NOT_APPLIED;
        }).when(settlementPort).settle(any());
        DisputeFinalizer second = new DisputeFinalizer(store, settlementService, true, 50);

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<DisputeFinalizer.Result> first = pool.submit(() -> finalizer.run());
            assertThat(insidePort.await(20, TimeUnit.SECONDS)).isTrue();
            // The first run holds the lease while it is inside the port: the second run finds nothing to claim.
            assertThat(second.run().settled()).isZero();
            release.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).settled()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        verify(settlementPort, times(1)).settle(any());
        assertThat(settlementOf(id).getLeaseOwner()).as("lease released after recording").isNull();
    }
}

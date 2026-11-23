package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.AppealVerdict;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeAppeal;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeSettlement;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.PartyResponse;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeAiSummaryData;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeAiSummaryData.ActorKind;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeAiSummaryData.HistoryItem;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeAiSummaryData.Outcome;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingDisputeStatusPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingLookupPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeEligibilityPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;
import com.gamagerecruiters.marketplace.modules.dispute.repository.DisputeRepository;

/** DisputeService.getDisputeForAiSummary (WP01 AI-005): only the agreed fields, no personal or internal data. */
@ExtendWith(MockitoExtension.class)
class DisputeAiSummaryDataTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final String DISPUTE_ID = "dispute-ai-1";
    private static final String BOOKING_ID = "booking-ai-1";
    private static final String CUSTOMER_ID = "65f1c0aa11bb22cc33a1c001";
    private static final String PROVIDER_ID = "65f1c0aa11bb22cc33a1p002";
    private static final String DECIDING_ADMIN = "65f1c0aa11bb22cc33a1a003";
    private static final String REVIEWING_ADMIN = "65f1c0aa11bb22cc33a1a004";
    private static final String ADMIN_NOTE = "Internal admin note: call logs checked";
    private static final String REVIEW_NOTE = "Internal review note: reviewer only";
    private static final String DESCRIPTION = "The provider never arrived for the booked slot";
    private static final String RESPONSE = "I arrived but nobody opened the door";
    private static final String GROUNDS = "Appeal grounds that are not part of the summary";

    @Mock
    private DisputeRepository disputeRepository;
    @Mock
    private DisputeBookingSyncStore syncStore;
    @Mock
    private BookingDisputeStatusPort bookingDisputeStatusPort;
    @Mock
    private BookingLookupPort bookingLookupPort;
    @Mock
    private DisputeEligibilityPort eligibilityPort;
    @Mock
    private PayoutHoldPort payoutHoldPort;

    private DisputeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DisputeServiceImpl(disputeRepository, syncStore, bookingDisputeStatusPort, bookingLookupPort,
                eligibilityPort, payoutHoldPort, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A dispute that went through every step: raised, answered, decided, appealed, changed on appeal, settled. */
    private static Dispute fullDispute() {
        Instant t0 = NOW.minus(Duration.ofDays(6));
        Dispute d = new Dispute();
        d.setId(DISPUTE_ID);
        d.setBookingId(BOOKING_ID);
        d.setCustomerId(CUSTOMER_ID);
        d.setProviderId(PROVIDER_ID);
        d.setRaisedBy(CUSTOMER_ID);
        d.setRaisedByRole(Role.CUSTOMER);
        d.setCategory(DisputeCategory.NO_SHOW);
        d.setDescription(DESCRIPTION);
        d.setEvidence(List.of("evidence/" + CUSTOMER_ID + "/door.jpg"));
        d.setCreatedAt(t0);
        d.setResponseDueAt(t0.plus(Duration.ofHours(72)));
        d.setBookingSyncState(BookingSyncState.SYNCED);
        d.setSyncLeaseOwner("lease-token-internal");
        d.changeStatus(DisputeStatus.AWAITING_RESPONSE, CUSTOMER_ID, Role.CUSTOMER, null, t0);

        PartyResponse response = new PartyResponse();
        response.setResponderId(PROVIDER_ID);
        response.setText(RESPONSE);
        response.setEvidence(List.of("evidence/" + PROVIDER_ID + "/gps.png"));
        response.setRespondedAt(t0.plus(Duration.ofHours(5)));
        d.setResponse(response);
        d.changeStatus(DisputeStatus.UNDER_REVIEW, PROVIDER_ID, Role.PROVIDER, null, t0.plus(Duration.ofHours(5)));

        Instant decidedAt = t0.plus(Duration.ofDays(1));
        d.setDecision(DisputeDecision.of(ResolutionAction.REFUND_PARTIAL, 40, true, DECIDING_ADMIN, Role.ADMIN,
                decidedAt, ADMIN_NOTE));
        d.setAppealDeadline(decidedAt.plus(Duration.ofHours(48)));
        d.changeStatus(DisputeStatus.RESOLVED, DECIDING_ADMIN, Role.ADMIN, ADMIN_NOTE, decidedAt);

        DisputeAppeal appeal = new DisputeAppeal();
        appeal.setAppealedBy(PROVIDER_ID);
        appeal.setAppealedByRole(Role.PROVIDER);
        appeal.setGrounds(GROUNDS);
        appeal.setFiledAt(decidedAt.plus(Duration.ofHours(10)));
        appeal.setVerdict(AppealVerdict.CHANGE);
        appeal.setReviewedBy(REVIEWING_ADMIN);
        appeal.setReviewedByRole(Role.ADMIN);
        appeal.setReviewedAt(decidedAt.plus(Duration.ofDays(2)));
        appeal.setReviewNote(REVIEW_NOTE);
        d.setAppeal(appeal);
        d.changeStatus(DisputeStatus.APPEALED, PROVIDER_ID, Role.PROVIDER, GROUNDS, decidedAt.plus(Duration.ofHours(10)));

        d.setFinalDecision(DisputeDecision.of(ResolutionAction.RELEASE_TO_PROVIDER, null, false, REVIEWING_ADMIN,
                Role.ADMIN, decidedAt.plus(Duration.ofDays(2)), REVIEW_NOTE));
        d.setFinalizedAt(decidedAt.plus(Duration.ofDays(2)));
        d.changeStatus(DisputeStatus.RESOLVED_FINAL, REVIEWING_ADMIN, Role.ADMIN, REVIEW_NOTE,
                decidedAt.plus(Duration.ofDays(2)));
        DisputeSettlement settlement = DisputeSettlement.pending();
        settlement.setLeaseOwner("settlement-lease-internal");
        d.setSettlement(settlement);
        com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceFile evidence =
                new com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceFile();
        evidence.setId("ev-internal-id");
        evidence.setPublicId("disputes/" + DISPUTE_ID + "/storage-ref-internal");
        evidence.setFormat("jpg");
        evidence.setFileName("front-door.jpg");
        evidence.setType(com.gamagerecruiters.marketplace.modules.dispute.domain.EvidenceType.JPG);
        evidence.setSizeBytes(1234);
        evidence.setUploadedByRole(Role.CUSTOMER);
        d.setEvidenceFiles(java.util.List.of(evidence));
        return d;
    }

    @Test
    @DisplayName("full mapping: id, status, category, raiser side, texts, empty evidence, history (status/time/role), "
            + "original decision and appeal result")
    void fullMapping() {
        Dispute dispute = fullDispute();
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));

        DisputeAiSummaryData data = service.getDisputeForAiSummary(DISPUTE_ID);

        assertThat(data.id()).isEqualTo(DISPUTE_ID);
        assertThat(data.status()).isEqualTo(DisputeStatus.RESOLVED_FINAL);
        assertThat(data.category()).isEqualTo(DisputeCategory.NO_SHOW);
        assertThat(data.raisedBy()).isEqualTo(DisputeAiSummaryData.Side.CUSTOMER);
        assertThat(data.raiserDescription()).isEqualTo(DESCRIPTION);
        assertThat(data.otherPartyResponse()).isEqualTo(RESPONSE);
        assertThat(data.evidence()).as("file name and type only")
                .containsExactly(new DisputeAiSummaryData.EvidenceItem("front-door.jpg", "JPG"));
        assertThat(data.statusHistory()).containsExactly(
                new HistoryItem(DisputeStatus.AWAITING_RESPONSE, dispute.getStatusHistory().get(0).getTimestamp(),
                        ActorKind.CUSTOMER),
                new HistoryItem(DisputeStatus.UNDER_REVIEW, dispute.getStatusHistory().get(1).getTimestamp(),
                        ActorKind.PROVIDER),
                new HistoryItem(DisputeStatus.RESOLVED, dispute.getStatusHistory().get(2).getTimestamp(),
                        ActorKind.ADMIN),
                new HistoryItem(DisputeStatus.APPEALED, dispute.getStatusHistory().get(3).getTimestamp(),
                        ActorKind.PROVIDER),
                new HistoryItem(DisputeStatus.RESOLVED_FINAL, dispute.getStatusHistory().get(4).getTimestamp(),
                        ActorKind.ADMIN));
        assertThat(data.decision()).isEqualTo(new Outcome(ResolutionAction.REFUND_PARTIAL, 40, true));
        assertThat(data.appeal().outcome()).isEqualTo(AppealStatus.CHANGED);
        assertThat(data.appeal().finalDecision()).isEqualTo(new Outcome(ResolutionAction.RELEASE_TO_PROVIDER, 0, false));
        assertThat(data.appeal().finalStatus()).isEqualTo(DisputeStatus.RESOLVED_FINAL);
    }

    @Test
    @DisplayName("before any decision: no decision, appeal NOT_AVAILABLE, no final result; a provider raiser and a "
            + "SYSTEM history entry are labelled without identity")
    void beforeDecision() {
        Dispute dispute = new Dispute();
        dispute.setId(DISPUTE_ID);
        dispute.setBookingId(BOOKING_ID);
        dispute.setCustomerId(CUSTOMER_ID);
        dispute.setProviderId(PROVIDER_ID);
        dispute.setRaisedBy(PROVIDER_ID);
        dispute.setRaisedByRole(Role.PROVIDER);
        dispute.setCategory(DisputeCategory.PROPERTY_DAMAGE);
        dispute.setDescription(DESCRIPTION);
        dispute.setBookingSyncState(BookingSyncState.SYNCED);
        dispute.changeStatus(DisputeStatus.AWAITING_RESPONSE, PROVIDER_ID, Role.PROVIDER, null, NOW);
        dispute.changeStatus(DisputeStatus.UNDER_REVIEW, "SYSTEM", null, "Response window closed", NOW);
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));

        DisputeAiSummaryData data = service.getDisputeForAiSummary(DISPUTE_ID);

        assertThat(data.raisedBy()).isEqualTo(DisputeAiSummaryData.Side.PROVIDER);
        assertThat(data.otherPartyResponse()).isNull();
        assertThat(data.decision()).isNull();
        assertThat(data.appeal().outcome()).isEqualTo(AppealStatus.NOT_AVAILABLE);
        assertThat(data.appeal().finalDecision()).isNull();
        assertThat(data.appeal().finalStatus()).isNull();
        assertThat(data.statusHistory()).extracting(HistoryItem::role)
                .containsExactly(ActorKind.PROVIDER, ActorKind.SYSTEM);
    }

    @Test
    @DisplayName("serialized JSON has no names, emails, party/admin ids, admin or review notes, appeal grounds, "
            + "evidence paths, settlement internals or bookingSyncState")
    void noPersonalOrInternalData() throws Exception {
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(fullDispute()));

        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(service.getDisputeForAiSummary(DISPUTE_ID));

        assertThat(json).doesNotContain(CUSTOMER_ID).doesNotContain(PROVIDER_ID)
                .doesNotContain("storage-ref-internal").doesNotContain("ev-internal-id")
                .doesNotContain(DECIDING_ADMIN).doesNotContain(REVIEWING_ADMIN)
                .doesNotContain(ADMIN_NOTE).doesNotContain(REVIEW_NOTE).doesNotContain(GROUNDS)
                .doesNotContain("evidence/").doesNotContain("lease")
                .doesNotContainIgnoringCase("settlement").doesNotContainIgnoringCase("bookingSync")
                .doesNotContain(BOOKING_ID).doesNotContain("@")
                .doesNotContainIgnoringCase("email").doesNotContainIgnoringCase("phone")
                .doesNotContain("fullName").doesNotContain("customerName").doesNotContain("providerName")
                .doesNotContain("decidedBy").doesNotContain("reviewedBy")
                .doesNotContain("customerId").doesNotContain("providerId").doesNotContain("actorId")
                .doesNotContain("\"note\"").doesNotContain("penaltyPercent");
        assertThat(json).contains("\"fileName\":\"front-door.jpg\"").contains(DESCRIPTION).contains(RESPONSE).contains("\"raisedBy\":\"CUSTOMER\"");
    }

    @Test
    @DisplayName("PENDING (unsynced) dispute -> 404 DISPUTE_NOT_FOUND, same as the party view")
    void pendingIsNotFound() {
        Dispute pending = fullDispute();
        pending.setBookingSyncState(BookingSyncState.PENDING);
        when(disputeRepository.findById(DISPUTE_ID)).thenReturn(Optional.of(pending));

        assertNotFound(catchThrowable(() -> service.getDisputeForAiSummary(DISPUTE_ID)));
        assertNotFound(catchThrowable(() -> service.getDisputeForParty(CUSTOMER_ID, DISPUTE_ID)));
    }

    @Test
    @DisplayName("unknown id -> 404 DISPUTE_NOT_FOUND")
    void unknownIsNotFound() {
        when(disputeRepository.findById("missing")).thenReturn(Optional.empty());

        assertNotFound(catchThrowable(() -> service.getDisputeForAiSummary("missing")));
    }

    private static void assertNotFound(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo("DISPUTE_NOT_FOUND");
    }
}

package com.gamagerecruiters.marketplace.modules.dispute.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.AppealVerdict;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeAppeal;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealStatus;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;

/** Party view hides admin data; appeal state and eligibility. */
class DisputeMapperTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final String ADMIN = "admin-a";
    private static final String NOTE = "secret internal note";

    private static Dispute resolved(Instant deadline) {
        Dispute dispute = new Dispute();
        dispute.setId("d-1");
        dispute.setBookingId("b-1");
        dispute.setCustomerId("cust-1");
        dispute.setProviderId("prov-1");
        dispute.setRaisedBy("cust-1");
        dispute.changeStatus(DisputeStatus.AWAITING_RESPONSE, "cust-1", Role.CUSTOMER, null, NOW.minusSeconds(7200));
        dispute.changeStatus(DisputeStatus.UNDER_REVIEW, "prov-1", Role.PROVIDER, null, NOW.minusSeconds(3600));
        dispute.changeStatus(DisputeStatus.RESOLVED, ADMIN, Role.ADMIN, "Decision: REFUND_FULL", NOW.minusSeconds(60));
        dispute.setDecision(DisputeDecision.of(ResolutionAction.REFUND_FULL, null, true, ADMIN, Role.ADMIN,
                NOW.minusSeconds(60), NOTE));
        dispute.setAppealDeadline(deadline);
        return dispute;
    }

    @Test
    @DisplayName("party view: no admin id, no note, admin history entries without actor and reason")
    void partyViewHidesAdminData() {
        DisputeDetailResponse view = DisputeMapper.toPartyDetail(resolved(NOW.plusSeconds(60)), "cust-1", NOW);

        assertThat(view.toString()).doesNotContain(ADMIN).doesNotContain(NOTE);
        DisputeDetailResponse.StatusHistoryView adminEntry = view.statusHistory().get(2);
        assertThat(adminEntry.actorId()).isNull();
        assertThat(adminEntry.reason()).isNull();
        assertThat(adminEntry.actorRole()).isEqualTo(Role.ADMIN);
        assertThat(view.statusHistory().get(1).actorId()).as("party entries unchanged").isEqualTo("prov-1");
    }

    @Test
    @DisplayName("party view of a dispute finalized by the finalizer (SYSTEM entry without a role) does not fail; the "
            + "entry has no actor, role or reason; neutral payment note while not carried out")
    void systemEntryWithoutRole() {
        Dispute dispute = resolved(NOW.minusSeconds(30));
        dispute.setFinalDecision(dispute.getDecision());
        dispute.changeStatus(DisputeStatus.RESOLVED_FINAL, "SYSTEM", null, "Appeal window closed without an appeal",
                NOW);
        dispute.setSettlement(com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeSettlement.pending());

        DisputeDetailResponse view = DisputeMapper.toPartyDetail(dispute, "cust-1", NOW);

        DisputeDetailResponse.StatusHistoryView system = view.statusHistory().get(3);
        assertThat(system.actorId()).isNull();
        assertThat(system.actorRole()).isNull();
        assertThat(system.reason()).isNull();
        assertThat(view.outcome().finalDecision()).isTrue();
        assertThat(view.outcome().paymentNote()).isEqualTo("Payment handling for this dispute is being processed.");
    }

    @Test
    @DisplayName("party view: the penalty flag is shown to the provider only")
    void penaltyOnlyForProvider() {
        Dispute dispute = resolved(NOW.plusSeconds(60));

        assertThat(DisputeMapper.toPartyDetail(dispute, "cust-1", NOW).outcome().providerPenaltyApplied()).isNull();
        assertThat(DisputeMapper.toPartyDetail(dispute, "prov-1", NOW).outcome().providerPenaltyApplied()).isTrue();
        assertThat(DisputeMapper.toPartyDetail(dispute, "prov-1", NOW).outcome().customerRefundPercent()).isEqualTo(100);
    }

    @Test
    @DisplayName("appeal eligibility: party, RESOLVED, before the deadline, no appeal yet")
    void appealEligibility() {
        assertThat(DisputeMapper.toPartyDetail(resolved(NOW.plusSeconds(60)), "cust-1", NOW).appealEligible()).isTrue();
        assertThat(DisputeMapper.toPartyDetail(resolved(NOW), "cust-1", NOW).appealEligible()).isFalse();
        assertThat(DisputeMapper.toPartyDetail(resolved(NOW.plusSeconds(60)), "stranger", NOW).appealEligible()).isFalse();
        assertThat(DisputeMapper.toDetail(resolved(NOW.plusSeconds(60))).appealEligible()).isFalse();
    }

    @Test
    @DisplayName("appeal status: NOT_AVAILABLE, OPEN, CLOSED, FILED, UPHELD, CHANGED")
    void appealStatuses() {
        Dispute undecided = new Dispute();
        undecided.setStatus(DisputeStatus.UNDER_REVIEW);
        assertThat(DisputeMapper.appealStatus(undecided, NOW)).isEqualTo(AppealStatus.NOT_AVAILABLE);

        assertThat(DisputeMapper.appealStatus(resolved(NOW.plusSeconds(60)), NOW)).isEqualTo(AppealStatus.OPEN);
        assertThat(DisputeMapper.appealStatus(resolved(NOW), NOW)).isEqualTo(AppealStatus.CLOSED);

        Dispute appealed = resolved(NOW.plusSeconds(60));
        appealed.setAppeal(new DisputeAppeal());
        assertThat(DisputeMapper.appealStatus(appealed, NOW)).isEqualTo(AppealStatus.FILED);
        appealed.getAppeal().setVerdict(AppealVerdict.UPHOLD);
        assertThat(DisputeMapper.appealStatus(appealed, NOW)).isEqualTo(AppealStatus.UPHELD);
        appealed.getAppeal().setVerdict(AppealVerdict.CHANGE);
        assertThat(DisputeMapper.appealStatus(appealed, NOW)).isEqualTo(AppealStatus.CHANGED);
    }

    @Test
    @DisplayName("admin view: includes the deciding admin, the note and names")
    void adminViewIsComplete() {
        AdminDisputeDetailResponse view = DisputeMapper.toAdminDetail(resolved(NOW.plusSeconds(60)), "Cust", "Prov", NOW,
                new AdminDisputeDetailResponse.Actions(false, false, "VIEW_ONLY"));

        assertThat(view.decision().decidedBy()).isEqualTo(ADMIN);
        assertThat(view.decision().note()).isEqualTo(NOTE);
        assertThat(view.decision().providerPenaltyPercent()).isEqualTo(30);
        assertThat(view.customerName()).isEqualTo("Cust");
        assertThat(view.statusHistory().get(2).actorId()).isEqualTo(ADMIN);
    }
}

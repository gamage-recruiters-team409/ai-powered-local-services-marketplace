package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AdminDisputeDetailResponse.Actions;

/** The admin detail's "actions" block follows exactly the resolve / appeal-decision rules of the backend. */
class DisputeAdminActionsTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final String ADMIN_A = "admin-a";
    private static final String ADMIN_B = "admin-b";

    private static Dispute dispute(DisputeStatus status, Instant responseDueAt) {
        Dispute dispute = new Dispute();
        dispute.setId("d-1");
        dispute.setResponseDueAt(responseDueAt);
        dispute.changeStatus(status, "cust-1", Role.CUSTOMER, null, NOW.minusSeconds(3600));
        return dispute;
    }

    private static Actions actions(Dispute dispute, String actor, Role role) {
        return DisputeAdminQueryService.actionsFor(dispute, actor, role, NOW);
    }

    @Test
    @DisplayName("UNDER_REVIEW, or AWAITING_RESPONSE after the response window -> ADMIN can resolve")
    void canResolve() {
        assertThat(actions(dispute(DisputeStatus.UNDER_REVIEW, NOW.plusSeconds(60)), ADMIN_A, Role.ADMIN))
                .isEqualTo(new Actions(true, false, null));
        assertThat(actions(dispute(DisputeStatus.AWAITING_RESPONSE, NOW), ADMIN_A, Role.ADMIN))
                .isEqualTo(new Actions(true, false, null));
    }

    @Test
    @DisplayName("AWAITING_RESPONSE inside the response window -> NOT_READY")
    void responseWindowOpen() {
        assertThat(actions(dispute(DisputeStatus.AWAITING_RESPONSE, NOW.plusSeconds(1)), ADMIN_A, Role.ADMIN))
                .isEqualTo(new Actions(false, false, "NOT_READY"));
    }

    @Test
    @DisplayName("APPEALED -> a different admin can decide; the deciding admin gets SAME_ADMIN_AS_DECISION")
    void appealReviewer() {
        Dispute appealed = dispute(DisputeStatus.APPEALED, NOW.minusSeconds(60));
        appealed.setDecision(DisputeDecision.of(ResolutionAction.RELEASE_TO_PROVIDER, null, false, ADMIN_A,
                Role.ADMIN, NOW.minusSeconds(600), "note"));

        assertThat(actions(appealed, ADMIN_B, Role.ADMIN)).isEqualTo(new Actions(false, true, null));
        assertThat(actions(appealed, ADMIN_A, Role.ADMIN)).isEqualTo(new Actions(false, false, "SAME_ADMIN_AS_DECISION"));
    }

    @ParameterizedTest
    @EnumSource(value = DisputeStatus.class, names = {"RESOLVED", "RESOLVED_FINAL", "CLOSED"})
    @DisplayName("already decided -> ALREADY_DECIDED, nothing allowed")
    void alreadyDecided(DisputeStatus status) {
        assertThat(actions(dispute(status, NOW.minusSeconds(60)), ADMIN_A, Role.ADMIN))
                .isEqualTo(new Actions(false, false, "ALREADY_DECIDED"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"SUPER_ADMIN", "MODERATOR", "CUSTOMER"})
    @DisplayName("anyone but ADMIN (SUPER_ADMIN is read-only) -> VIEW_ONLY even on a decidable case")
    void viewOnly(Role role) {
        assertThat(actions(dispute(DisputeStatus.UNDER_REVIEW, NOW.minusSeconds(60)), ADMIN_A, role))
                .isEqualTo(new Actions(false, false, "VIEW_ONLY"));
        assertThat(actions(dispute(DisputeStatus.UNDER_REVIEW, NOW.minusSeconds(60)), null, Role.ADMIN))
                .isEqualTo(new Actions(false, false, "VIEW_ONLY"));
    }
}

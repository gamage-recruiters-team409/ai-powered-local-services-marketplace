package com.gamagerecruiters.marketplace.modules.dispute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.AppealVerdict;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeAppeal;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeDecision;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.ResolutionAction;
import com.gamagerecruiters.marketplace.modules.dispute.domain.StatusHistoryEntry;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealDecisionRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.AppealDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.ResolveDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.repository.DisputeRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Per-dispute rules and error codes of the decision, appeal and appeal decision (store mocked). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeResolutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final String ID = "dispute-1";
    private static final String CUSTOMER = "cust-1";
    private static final String PROVIDER = "prov-1";
    private static final String ADMIN_A = "admin-a";
    private static final String ADMIN_B = "admin-b";
    private static final String NOTE = "secret internal note";
    private static final String GROUNDS = "private grounds text";

    @Mock
    private DisputeRepository repository;
    @Mock
    private DisputeResolutionStore store;
    @Mock
    private DisputeSettlementService settlementService;
    @Mock
    private DisputeAdminQueryService queryService;

    private DisputeResolutionService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new DisputeResolutionService(repository, store, settlementService, queryService,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(96));
        serviceLogger = (Logger) LoggerFactory.getLogger(DisputeResolutionService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
    }

    private Dispute stored(DisputeStatus status) {
        Dispute dispute = new Dispute();
        dispute.setId(ID);
        dispute.setVersion(7L);
        dispute.setBookingId("booking-1");
        dispute.setCustomerId(CUSTOMER);
        dispute.setProviderId(PROVIDER);
        dispute.setRaisedBy(CUSTOMER);
        dispute.setCategory(DisputeCategory.POOR_QUALITY);
        dispute.setBookingSyncState(BookingSyncState.SYNCED);
        dispute.setResponseDueAt(NOW.minusSeconds(1));
        dispute.setStatus(status);
        when(repository.findById(ID)).thenReturn(Optional.of(dispute));
        return dispute;
    }

    private Dispute resolvedBy(String adminId, Instant deadline) {
        Dispute dispute = stored(DisputeStatus.RESOLVED);
        dispute.setDecision(DisputeDecision.of(ResolutionAction.REFUND_PARTIAL, 40, true, adminId, Role.ADMIN,
                NOW.minusSeconds(3600), NOTE));
        dispute.setAppealDeadline(deadline);
        return dispute;
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(((ApiException) thrown).getErrorCode()).isEqualTo(code);
    }

    private void assertLogsHaveNoText() {
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(line -> assertThat(line).doesNotContain(NOTE).doesNotContain(GROUNDS));
    }

    // ---------- decision validation ----------

    static Stream<Arguments> invalidDecisions() {
        return Stream.of(
                Arguments.of(ResolutionAction.REFUND_PARTIAL, null, false, "INVALID_REFUND_PERCENT"),
                Arguments.of(ResolutionAction.REFUND_PARTIAL, 0, false, "INVALID_REFUND_PERCENT"),
                Arguments.of(ResolutionAction.REFUND_PARTIAL, 100, false, "INVALID_REFUND_PERCENT"),
                Arguments.of(ResolutionAction.REFUND_FULL, 50, false, "INVALID_REFUND_PERCENT"),
                Arguments.of(ResolutionAction.RELEASE_TO_PROVIDER, 10, false, "INVALID_REFUND_PERCENT"),
                Arguments.of(ResolutionAction.RELEASE_TO_PROVIDER, null, true, "PENALTY_REQUIRES_REFUND"));
    }

    @ParameterizedTest(name = "{0} percent={1} penalty={2} -> {3}")
    @MethodSource("invalidDecisions")
    @DisplayName("decision rules: percent only for REFUND_PARTIAL (1-99), penalty only with a refund -> 400")
    void invalidDecisionRejected(ResolutionAction action, Integer percent, boolean penalty, String code) {
        assertApiError(catchThrowable(() -> service.resolve(ADMIN_A, Role.ADMIN, ID,
                new ResolveDisputeRequest(action, percent, penalty, NOTE))), HttpStatus.BAD_REQUEST, code);
        verifyNoInteractions(store);
    }

    static Stream<Arguments> validDecisions() {
        return Stream.of(
                Arguments.of(ResolutionAction.REFUND_PARTIAL, 1, true, 1, 30),
                Arguments.of(ResolutionAction.REFUND_PARTIAL, 99, false, 99, 0),
                Arguments.of(ResolutionAction.REFUND_FULL, null, true, 100, 30),
                Arguments.of(ResolutionAction.RELEASE_TO_PROVIDER, null, false, 0, 0));
    }

    @ParameterizedTest(name = "{0} percent={1} penalty={2}")
    @MethodSource("validDecisions")
    @DisplayName("decision: stored with the effective refund percent and the 30% penalty constant")
    void validDecisionStored(ResolutionAction action, Integer percent, boolean penalty, int effective, int penaltyPct) {
        Dispute current = stored(DisputeStatus.UNDER_REVIEW);
        when(store.resolve(eq(ID), eq(DisputeStatus.UNDER_REVIEW), eq(NOW), any(), any(), any()))
                .thenReturn(Optional.of(current));

        service.resolve(ADMIN_A, Role.ADMIN, ID, new ResolveDisputeRequest(action, percent, penalty, "  " + NOTE + " "));

        ArgumentCaptor<DisputeDecision> decision = ArgumentCaptor.forClass(DisputeDecision.class);
        ArgumentCaptor<StatusHistoryEntry> entry = ArgumentCaptor.forClass(StatusHistoryEntry.class);
        verify(store).resolve(eq(ID), eq(DisputeStatus.UNDER_REVIEW), eq(NOW), decision.capture(),
                eq(NOW.plus(DisputeRules.APPEAL_WINDOW)), entry.capture());
        assertThat(decision.getValue().getCustomerRefundPercent()).isEqualTo(effective);
        assertThat(decision.getValue().getProviderPenaltyPercent()).isEqualTo(penaltyPct);
        assertThat(decision.getValue().getDecidedBy()).isEqualTo(ADMIN_A);
        assertThat(decision.getValue().getNote()).isEqualTo(NOTE);
        assertThat(entry.getValue().getFromStatus()).isEqualTo(DisputeStatus.UNDER_REVIEW);
        assertThat(entry.getValue().getToStatus()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(entry.getValue().getActorId()).isEqualTo(ADMIN_A);
        assertThat(entry.getValue().getReason()).doesNotContain(NOTE);
        assertLogsHaveNoText();
    }

    // ---------- decision status rules ----------

    @ParameterizedTest
    @EnumSource(value = DisputeStatus.class, names = {"OPEN", "RESOLVED", "APPEALED", "RESOLVED_FINAL", "CLOSED"})
    @DisplayName("decision: only from UNDER_REVIEW or an expired AWAITING_RESPONSE; anything else -> 409")
    void resolveWrongStatus(DisputeStatus status) {
        stored(status);

        assertApiError(catchThrowable(() -> service.resolve(ADMIN_A, Role.ADMIN, ID,
                new ResolveDisputeRequest(ResolutionAction.REFUND_FULL, null, false, NOTE))),
                HttpStatus.CONFLICT, "INVALID_DISPUTE_STATUS");
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("decision: AWAITING_RESPONSE while the response window is open -> 409; once passed -> allowed")
    void resolveAwaitingResponse() {
        Dispute current = stored(DisputeStatus.AWAITING_RESPONSE);
        current.setResponseDueAt(NOW.plusSeconds(1));
        ResolveDisputeRequest request = new ResolveDisputeRequest(ResolutionAction.REFUND_FULL, null, false, NOTE);

        assertApiError(catchThrowable(() -> service.resolve(ADMIN_A, Role.ADMIN, ID, request)),
                HttpStatus.CONFLICT, "INVALID_DISPUTE_STATUS");

        current.setResponseDueAt(NOW);
        when(store.resolve(any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(current));
        service.resolve(ADMIN_A, Role.ADMIN, ID, request);
        verify(store).resolve(eq(ID), eq(DisputeStatus.AWAITING_RESPONSE), eq(NOW), any(), any(), any());
    }

    @Test
    @DisplayName("decision: another admin won the race (conditional update empty) -> 409")
    void resolveLostRace() {
        stored(DisputeStatus.UNDER_REVIEW);
        when(store.resolve(any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        assertApiError(catchThrowable(() -> service.resolve(ADMIN_A, Role.ADMIN, ID,
                new ResolveDisputeRequest(ResolutionAction.REFUND_FULL, null, false, NOTE))),
                HttpStatus.CONFLICT, "INVALID_DISPUTE_STATUS");
    }

    @Test
    @DisplayName("decision: unknown or PENDING-sync dispute -> 404")
    void resolveNotFound() {
        Dispute pending = stored(DisputeStatus.UNDER_REVIEW);
        pending.setBookingSyncState(BookingSyncState.PENDING);

        assertApiError(catchThrowable(() -> service.resolve(ADMIN_A, Role.ADMIN, ID,
                new ResolveDisputeRequest(ResolutionAction.REFUND_FULL, null, false, NOTE))),
                HttpStatus.NOT_FOUND, "DISPUTE_NOT_FOUND");
    }

    // ---------- appeal ----------

    @Test
    @DisplayName("appeal: stored with grounds trimmed and reviewDueAt = filedAt + review SLA (96h)")
    void appealStored() {
        Dispute current = resolvedBy(ADMIN_A, NOW.plusSeconds(60));
        when(store.fileAppeal(eq(ID), eq(PROVIDER), eq(NOW), any(), any())).thenReturn(Optional.of(current));

        service.appeal(PROVIDER, Role.PROVIDER, ID, new AppealDisputeRequest("  " + GROUNDS + "  ", List.of("e.jpg")));

        ArgumentCaptor<DisputeAppeal> appeal = ArgumentCaptor.forClass(DisputeAppeal.class);
        verify(store).fileAppeal(eq(ID), eq(PROVIDER), eq(NOW), appeal.capture(), any());
        assertThat(appeal.getValue().getGrounds()).isEqualTo(GROUNDS);
        assertThat(appeal.getValue().getAppealedByRole()).isEqualTo(Role.PROVIDER);
        assertThat(appeal.getValue().getReviewDueAt()).isEqualTo(NOW.plus(Duration.ofHours(96)));
        assertThat(appeal.getValue().getEvidence()).containsExactly("e.jpg");
        assertLogsHaveNoText();
    }

    @Test
    @DisplayName("appeal: not a party -> 403 NOT_DISPUTE_PARTY")
    void appealNonParty() {
        resolvedBy(ADMIN_A, NOW.plusSeconds(60));

        assertApiError(catchThrowable(() -> service.appeal("stranger", Role.CUSTOMER, ID,
                new AppealDisputeRequest("x", null))), HttpStatus.FORBIDDEN, "NOT_DISPUTE_PARTY");
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("appeal: window passed -> 409 APPEAL_WINDOW_CLOSED (deadline itself counts as closed)")
    void appealWindowClosed() {
        resolvedBy(ADMIN_A, NOW);

        assertApiError(catchThrowable(() -> service.appeal(CUSTOMER, Role.CUSTOMER, ID,
                new AppealDisputeRequest("x", null))), HttpStatus.CONFLICT, "APPEAL_WINDOW_CLOSED");
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("appeal: already appealed -> 409 APPEAL_ALREADY_FILED; not RESOLVED -> 409 INVALID_DISPUTE_STATUS")
    void appealStatusRules() {
        Dispute current = resolvedBy(ADMIN_A, NOW.plusSeconds(60));
        current.setAppeal(new DisputeAppeal());
        current.setStatus(DisputeStatus.APPEALED);
        assertApiError(catchThrowable(() -> service.appeal(CUSTOMER, Role.CUSTOMER, ID,
                new AppealDisputeRequest("x", null))), HttpStatus.CONFLICT, "APPEAL_ALREADY_FILED");

        stored(DisputeStatus.UNDER_REVIEW);
        assertApiError(catchThrowable(() -> service.appeal(CUSTOMER, Role.CUSTOMER, ID,
                new AppealDisputeRequest("x", null))), HttpStatus.CONFLICT, "INVALID_DISPUTE_STATUS");
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("appeal: the other party appealed first (conditional update empty) -> 409 APPEAL_ALREADY_FILED")
    void appealLostRace() {
        Dispute before = resolvedBy(ADMIN_A, NOW.plusSeconds(60));
        Dispute after = resolvedBy(ADMIN_A, NOW.plusSeconds(60));
        after.setAppeal(new DisputeAppeal());
        after.setStatus(DisputeStatus.APPEALED);
        when(repository.findById(ID)).thenReturn(Optional.of(before), Optional.of(after));
        when(store.fileAppeal(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        assertApiError(catchThrowable(() -> service.appeal(CUSTOMER, Role.CUSTOMER, ID,
                new AppealDisputeRequest("x", null))), HttpStatus.CONFLICT, "APPEAL_ALREADY_FILED");
    }

    // ---------- appeal decision ----------

    private Dispute appealedDecidedBy(String adminId) {
        Dispute current = resolvedBy(adminId, NOW.minusSeconds(1));
        current.setStatus(DisputeStatus.APPEALED);
        current.setAppeal(new DisputeAppeal());
        return current;
    }

    @Test
    @DisplayName("appeal decision: the admin who decided -> 403 APPEAL_REVIEWER_MUST_DIFFER, nothing stored or settled")
    void sameAdminRejected() {
        appealedDecidedBy(ADMIN_A);

        assertApiError(catchThrowable(() -> service.decideAppeal(ADMIN_A, Role.ADMIN, ID,
                new AppealDecisionRequest(AppealVerdict.UPHOLD, null, null, null, NOTE))),
                HttpStatus.FORBIDDEN, "APPEAL_REVIEWER_MUST_DIFFER");
        verifyNoInteractions(store, settlementService);
    }

    @ParameterizedTest
    @EnumSource(value = DisputeStatus.class, names = {"UNDER_REVIEW", "RESOLVED", "RESOLVED_FINAL"})
    @DisplayName("appeal decision: not APPEALED -> 409 INVALID_DISPUTE_STATUS")
    void decideWrongStatus(DisputeStatus status) {
        appealedDecidedBy(ADMIN_A).setStatus(status);

        assertApiError(catchThrowable(() -> service.decideAppeal(ADMIN_B, Role.ADMIN, ID,
                new AppealDecisionRequest(AppealVerdict.UPHOLD, null, null, null, NOTE))),
                HttpStatus.CONFLICT, "INVALID_DISPUTE_STATUS");
        verifyNoInteractions(store, settlementService);
    }

    static Stream<Arguments> invalidAppealDecisions() {
        return Stream.of(
                Arguments.of(new AppealDecisionRequest(AppealVerdict.UPHOLD, ResolutionAction.REFUND_FULL, null, null, NOTE),
                        "INVALID_APPEAL_DECISION"),
                Arguments.of(new AppealDecisionRequest(AppealVerdict.UPHOLD, null, 50, null, NOTE),
                        "INVALID_APPEAL_DECISION"),
                Arguments.of(new AppealDecisionRequest(AppealVerdict.CHANGE, null, null, null, NOTE),
                        "INVALID_APPEAL_DECISION"),
                Arguments.of(new AppealDecisionRequest(AppealVerdict.CHANGE, ResolutionAction.RELEASE_TO_PROVIDER, null,
                        true, NOTE), "PENALTY_REQUIRES_REFUND"),
                Arguments.of(new AppealDecisionRequest(AppealVerdict.CHANGE, ResolutionAction.REFUND_PARTIAL, 0, null,
                        NOTE), "INVALID_REFUND_PERCENT"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("invalidAppealDecisions")
    @DisplayName("appeal decision: UPHOLD carries no decision fields; CHANGE follows the decision rules -> 400")
    void invalidAppealDecision(AppealDecisionRequest request, String code) {
        assertApiError(catchThrowable(() -> service.decideAppeal(ADMIN_B, Role.ADMIN, ID, request)),
                HttpStatus.BAD_REQUEST, code);
        verifyNoInteractions(store, settlementService);
    }

    @Test
    @DisplayName("appeal decision UPHOLD: the original decision becomes binding (version-checked), then settlement")
    void upholdKeepsOriginal() {
        Dispute current = appealedDecidedBy(ADMIN_A);
        when(store.decideAppeal(any(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(current));

        service.decideAppeal(ADMIN_B, Role.ADMIN, ID,
                new AppealDecisionRequest(AppealVerdict.UPHOLD, null, null, null, " " + NOTE + " "));

        ArgumentCaptor<DisputeDecision> binding = ArgumentCaptor.forClass(DisputeDecision.class);
        verify(store).decideAppeal(eq(ID), eq(7L), eq(ADMIN_B), eq(Role.ADMIN), eq(AppealVerdict.UPHOLD), eq(NOTE),
                binding.capture(), eq(NOW), any());
        assertThat(binding.getValue()).isSameAs(current.getDecision());
        verify(settlementService).settle(ID);
        assertLogsHaveNoText();
    }

    @Test
    @DisplayName("appeal decision CHANGE: a new binding decision by the reviewer")
    void changeReplacesDecision() {
        Dispute current = appealedDecidedBy(ADMIN_A);
        when(store.decideAppeal(any(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(current));

        service.decideAppeal(ADMIN_B, Role.ADMIN, ID,
                new AppealDecisionRequest(AppealVerdict.CHANGE, ResolutionAction.RELEASE_TO_PROVIDER, null, false, NOTE));

        ArgumentCaptor<DisputeDecision> binding = ArgumentCaptor.forClass(DisputeDecision.class);
        verify(store).decideAppeal(eq(ID), eq(7L), eq(ADMIN_B), any(), eq(AppealVerdict.CHANGE), any(),
                binding.capture(), any(), any());
        assertThat(binding.getValue().getAction()).isEqualTo(ResolutionAction.RELEASE_TO_PROVIDER);
        assertThat(binding.getValue().getCustomerRefundPercent()).isZero();
        assertThat(binding.getValue().getDecidedBy()).isEqualTo(ADMIN_B);
        verify(settlementService).settle(ID);
    }

    @Test
    @DisplayName("appeal decision: conditional update empty and nothing else explains it -> 409 DISPUTE_CONCURRENT_UPDATE")
    void decideLostRace() {
        appealedDecidedBy(ADMIN_A);
        when(store.decideAppeal(any(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertApiError(catchThrowable(() -> service.decideAppeal(ADMIN_B, Role.ADMIN, ID,
                new AppealDecisionRequest(AppealVerdict.UPHOLD, null, null, null, NOTE))),
                HttpStatus.CONFLICT, "DISPUTE_CONCURRENT_UPDATE");
        verify(settlementService, never()).settle(anyString());
    }
}

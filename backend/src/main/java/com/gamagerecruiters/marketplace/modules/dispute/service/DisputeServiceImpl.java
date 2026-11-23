package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.dispute.domain.BookingSyncState;
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeCategory;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeRules;
import com.gamagerecruiters.marketplace.modules.dispute.domain.DisputeStatus;
import com.gamagerecruiters.marketplace.modules.dispute.domain.PartyResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeAiSummaryData;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.SubmitDisputeResponseRequest;
import com.gamagerecruiters.marketplace.modules.dispute.mapper.DisputeMapper;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingDisputeStatusPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingLookupPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeEligibilityPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;
import com.gamagerecruiters.marketplace.modules.dispute.repository.DisputeRepository;

@Service
public class DisputeServiceImpl implements DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeServiceImpl.class);

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final Duration DEFAULT_SYNC_LEASE = Duration.ofMinutes(5);

    private final DisputeRepository disputeRepository;
    private final DisputeBookingSyncStore syncStore;
    private final BookingDisputeStatusPort bookingDisputeStatusPort;
    /** Re-reads the booking before an intent is removed after a rejection. */
    private final BookingLookupPort bookingLookupPort;
    private final DisputeEligibilityPort disputeEligibilityPort;
    private final PayoutHoldPort payoutHoldPort;
    private final Clock clock;
    /** How long a raise request holds its intent record (same setting as the reconciler's lease). */
    private final Duration syncLease;

    @Autowired
    public DisputeServiceImpl(DisputeRepository disputeRepository,
                              DisputeBookingSyncStore syncStore,
                              BookingDisputeStatusPort bookingDisputeStatusPort,
                              BookingLookupPort bookingLookupPort,
                              DisputeEligibilityPort disputeEligibilityPort,
                              PayoutHoldPort payoutHoldPort,
                              @Value("${app.dispute.booking-sync.lease:PT5M}") Duration syncLease) {
        this(disputeRepository, syncStore, bookingDisputeStatusPort, bookingLookupPort, disputeEligibilityPort,
                payoutHoldPort, Clock.systemUTC(), syncLease);
    }

    DisputeServiceImpl(DisputeRepository disputeRepository,
                       DisputeBookingSyncStore syncStore,
                       BookingDisputeStatusPort bookingDisputeStatusPort,
                       BookingLookupPort bookingLookupPort,
                       DisputeEligibilityPort disputeEligibilityPort,
                       PayoutHoldPort payoutHoldPort,
                       Clock clock) {
        this(disputeRepository, syncStore, bookingDisputeStatusPort, bookingLookupPort, disputeEligibilityPort,
                payoutHoldPort, clock, DEFAULT_SYNC_LEASE);
    }

    DisputeServiceImpl(DisputeRepository disputeRepository,
                       DisputeBookingSyncStore syncStore,
                       BookingDisputeStatusPort bookingDisputeStatusPort,
                       BookingLookupPort bookingLookupPort,
                       DisputeEligibilityPort disputeEligibilityPort,
                       PayoutHoldPort payoutHoldPort,
                       Clock clock,
                       Duration syncLease) {
        if (syncLease == null || syncLease.isNegative() || syncLease.isZero()) {
            throw new IllegalArgumentException("app.dispute.booking-sync.lease must be greater than zero");
        }
        this.disputeRepository = disputeRepository;
        this.syncStore = syncStore;
        this.bookingDisputeStatusPort = bookingDisputeStatusPort;
        this.bookingLookupPort = bookingLookupPort;
        this.disputeEligibilityPort = disputeEligibilityPort;
        this.payoutHoldPort = payoutHoldPort;
        this.clock = clock;
        this.syncLease = syncLease;
    }

    /**
     * Raise flow (the Booking module decides eligibility; the dispute module never stays out of step with it):
     * <ol>
     *   <li>A party of an existing visible dispute for the booking (any status, also RESOLVED_FINAL): 409
     *       DISPUTE_ALREADY_EXISTS, nothing else is called.</li>
     *   <li>BookingFacade.assertDisputeEligible (via {@link DisputeEligibilityPort}): read-only; its errors (401, 403,
     *       404, 409 INVALID_STATUS_TRANSITION / DISPUTE_WINDOW_CLOSED) are returned unchanged. The caller must also
     *       be a party (checked here too, so the reporter and counterparty are always right).</li>
     *   <li>Reject a second dispute for the booking. A PENDING intent record of the same caller is a retry: the
     *       request first takes the record's lease (atomic). If another request or the reconciler is working on it,
     *       the retry gets 503 DISPUTE_SYNC_PENDING and changes nothing, so two workers never move the booking or
     *       remove the record at the same time.</li>
     *   <li>Otherwise insert the dispute as PENDING, already holding its lease (invisible; the unique active-booking
     *       index still blocks duplicates).</li>
     *   <li>Booking not yet DISPUTED: BookingFacade.markDisputed.
     *     <ul>
     *       <li>Confirmed rejection (404, 403, 409 INVALID_STATUS_TRANSITION, 409 DISPUTE_WINDOW_CLOSED): the booking
     *           is re-read first. DISPUTED: the dispute is completed instead. Not DISPUTED: the record is deleted
     *           (only while this request still holds its lease) and the facade error returned unchanged. Re-read
     *           fails: the record is kept for the reconciler and the facade error returned unchanged.</li>
     *       <li>Any other failure (e.g. a database timeout, 401, BOOKING_UPDATE_CONFLICT): the record is KEPT and the
     *           lease released; {@link DisputeBookingSyncReconciler} checks the booking's real state and completes
     *           or removes it. A Booking-module answer is returned unchanged, anything else as 503
     *           DISPUTE_SYNC_PENDING.</li>
     *     </ul>
     *       Booking already DISPUTED (and eligible): no booking change needed.</li>
     *   <li>Mark the dispute SYNCED.</li>
     * </ol>
     * If the SYNCED update fails or the process stops in between, the PENDING record stays; a retry of the same
     * request or the reconciler completes it (booking DISPUTED) or removes it (booking not DISPUTED after a threshold).
     */
    @Override
    public DisputeDetailResponse raiseDispute(String actorId, Role actorRole, RaiseDisputeRequest request) {
        // One dispute per booking, in any status: also after finalization, when the booking may have left DISPUTED and
        // the active-booking key was released. Only a party learns that a dispute exists; anyone else gets the Booking
        // module's own answer (403/404) below.
        if (disputeRepository.findVisiblePartiesByBookingId(request.bookingId()).stream()
                .anyMatch(prior -> prior.isParty(actorId))) {
            throw duplicateDispute();
        }

        // The Booking module decides eligibility, including every time rule (BR-008). No completion-based window here.
        BookingSnapshot booking = disputeEligibilityPort.assertEligible(request.bookingId());

        // Same rule as the Booking module. Kept so the reporter/counterparty are always the booking's own parties.
        if (!booking.isParty(actorId)) {
            throw new AccessDeniedException("Access denied: you are not a participant of this booking");
        }

        Optional<Dispute> existing = disputeRepository.findByActiveBookingKey(booking.bookingId());
        if (existing.isPresent()) {
            Dispute current = existing.get();
            if (current.isVisible() || !actorId.equals(current.getRaisedBy())) {
                throw duplicateDispute();
            }
            return finish(completeIntent(claimOwnIntent(current), booking));
        }

        Dispute intent = insert(newDispute(booking, actorId, actorRole, request));
        return finish(completeIntent(intent, booking));
    }

    /**
     * A retry of the caller's own unfinished raise. Only one worker may work on the record at a time: if a concurrent
     * request or the reconciler holds it, nothing is done and the caller is asked to try again.
     */
    private Dispute claimOwnIntent(Dispute current) {
        Optional<Dispute> claimed;
        try {
            claimed = syncStore.claimById(current.getId(), clock.instant(), syncLease);
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_SYNC_PENDING disputeId={} bookingId={} actorId={} cause={}",
                    current.getId(), current.getBookingId(), current.getRaisedBy(), e.getClass().getSimpleName());
            throw syncPending();
        }
        return claimed.orElseThrow(() -> {
            log.warn("event=DISPUTE_INTENT_BUSY disputeId={} bookingId={} actorId={}",
                    current.getId(), current.getBookingId(), current.getRaisedBy());
            return syncPending();
        });
    }

    /** Moves the booking to DISPUTED unless it already is, then marks the intent record SYNCED. */
    private Dispute completeIntent(Dispute intent, BookingSnapshot booking) {
        if (!booking.alreadyDisputed()) {
            try {
                bookingDisputeStatusPort.markDisputed(intent.getBookingId(),
                        bookingTimelineReason(intent.getCategory()));
            } catch (RuntimeException e) {
                return afterBookingUpdateFailed(intent, e);
            }
        }
        return completeSync(intent);
    }

    private Dispute newDispute(BookingSnapshot booking, String actorId, Role actorRole, RaiseDisputeRequest request) {
        Instant now = clock.instant();
        Dispute dispute = new Dispute();
        dispute.setBookingId(booking.bookingId());
        dispute.setCustomerId(booking.customerId());
        dispute.setProviderId(booking.providerId());
        dispute.setRaisedBy(actorId);
        dispute.setRaisedByRole(actorRole);
        dispute.setCategory(request.category());
        dispute.setDescription(request.description().strip());
        dispute.setEvidence(request.evidence());
        dispute.setCreatedAt(now);
        dispute.setResponseDueAt(now.plus(DisputeRules.RESPONSE_WINDOW));
        dispute.setBookingSyncState(BookingSyncState.PENDING);
        // Inserted already leased to this request, so neither a retry nor the reconciler touches it meanwhile.
        dispute.setSyncLeaseUntil(now.plus(syncLease));
        dispute.setSyncLeaseOwner(DisputeBookingSyncStore.newLeaseOwner());
        dispute.changeStatus(DisputeStatus.AWAITING_RESPONSE, actorId, actorRole, null, now);
        return dispute;
    }

    /** Insert only. The booking has not been changed yet, so a failure here leaves nothing to undo. */
    private Dispute insert(Dispute dispute) {
        try {
            return disputeRepository.insert(dispute);
        } catch (DuplicateKeyException e) {
            // A concurrent request holds the booking's active-dispute key (unique index).
            throw duplicateDispute();
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_SAVE_FAILED bookingId={} actorId={} cause={}",
                    dispute.getBookingId(), dispute.getRaisedBy(), e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DISPUTE_SAVE_FAILED",
                    "The dispute could not be saved. Please try again");
        }
    }

    /**
     * markDisputed failed. The intent is removed only when the rejection is confirmed AND a re-read shows the booking
     * is not DISPUTED; if it is DISPUTED the dispute is completed instead. Every other case keeps the intent (lease
     * released) for the reconciler to verify against the booking's real state.
     */
    private Dispute afterBookingUpdateFailed(Dispute intent, RuntimeException error) {
        if (!isConfirmedRejection(error)) {
            log.error("event=DISPUTE_BOOKING_UPDATE_UNCERTAIN disputeId={} bookingId={} actorId={} cause={}",
                    intent.getId(), intent.getBookingId(), intent.getRaisedBy(), error.getClass().getSimpleName());
            releaseLease(intent);
            throw isBookingModuleAnswer(error) ? error : syncPending();
        }

        Boolean disputed = bookingIsDisputed(intent);
        if (disputed == null) {
            releaseLease(intent);
            throw error;
        }
        if (disputed) {
            log.warn("event=DISPUTE_REJECTED_BUT_BOOKING_DISPUTED disputeId={} bookingId={}",
                    intent.getId(), intent.getBookingId());
            return completeSync(intent);
        }
        compensate(intent);
        throw error;
    }

    /** Re-reads the booking. True/false = DISPUTED or not (an unknown booking is not DISPUTED); null = unknown. */
    private Boolean bookingIsDisputed(Dispute intent) {
        try {
            return bookingLookupPort.getBooking(intent.getBookingId()).alreadyDisputed();
        } catch (ResourceNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_BOOKING_REREAD_FAILED disputeId={} bookingId={} cause={}",
                    intent.getId(), intent.getBookingId(), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * True only for the Booking module's answers that say the change was NOT applied: 404 (booking not found),
     * 403 (not allowed), 409 INVALID_STATUS_TRANSITION and 409 DISPUTE_WINDOW_CLOSED. All are raised before saving.
     */
    static boolean isConfirmedRejection(RuntimeException e) {
        if (e instanceof AccessDeniedException) {
            return true;
        }
        if (e instanceof ApiException apiException) {
            HttpStatus status = apiException.getStatus();
            String code = apiException.getErrorCode();
            return status == HttpStatus.NOT_FOUND || status == HttpStatus.FORBIDDEN
                    || (status == HttpStatus.CONFLICT
                        && ("INVALID_STATUS_TRANSITION".equals(code) || "DISPUTE_WINDOW_CLOSED".equals(code)));
        }
        return false;
    }

    /** An error answered by the Booking module itself (returned to the caller unchanged). */
    private static boolean isBookingModuleAnswer(RuntimeException e) {
        return e instanceof ApiException || e instanceof AccessDeniedException;
    }

    private void compensate(Dispute intent) {
        try {
            if (!syncStore.deleteIfPending(intent.getId(), intent.getSyncLeaseOwner())) {
                log.warn("event=DISPUTE_INTENT_NOT_REMOVED disputeId={} bookingId={}",
                        intent.getId(), intent.getBookingId());
            }
        } catch (RuntimeException e) {
            // The reconciler removes it later once it confirms the booking is not DISPUTED.
            log.error("event=DISPUTE_INTENT_COMPENSATION_FAILED disputeId={} bookingId={} cause={}",
                    intent.getId(), intent.getBookingId(), e.getClass().getSimpleName());
        }
    }

    /** Best effort: lets a retry or the reconciler take the record at once. If it fails, the lease just expires. */
    private void releaseLease(Dispute intent) {
        try {
            syncStore.releaseLease(intent.getId(), intent.getSyncLeaseOwner());
        } catch (RuntimeException e) {
            log.warn("event=DISPUTE_INTENT_RELEASE_FAILED disputeId={} bookingId={} cause={}",
                    intent.getId(), intent.getBookingId(), e.getClass().getSimpleName());
        }
    }

    /** PENDING -> SYNCED. If that cannot be confirmed now, the record stays PENDING for a retry or the reconciler. */
    private Dispute completeSync(Dispute intent) {
        Optional<Dispute> synced;
        try {
            synced = syncStore.markSynced(intent.getId(), intent.getSyncLeaseOwner())
                    // Completed meanwhile by another thread or the reconciler.
                    .or(() -> disputeRepository.findById(intent.getId()).filter(Dispute::isVisible));
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_SYNC_PENDING disputeId={} bookingId={} actorId={} cause={}",
                    intent.getId(), intent.getBookingId(), intent.getRaisedBy(), e.getClass().getSimpleName());
            releaseLease(intent);
            throw syncPending();
        }
        return synced.orElseThrow(() -> {
            log.error("event=DISPUTE_SYNC_PENDING disputeId={} bookingId={} actorId={} cause=NOT_PENDING",
                    intent.getId(), intent.getBookingId(), intent.getRaisedBy());
            return syncPending();
        });
    }

    private DisputeDetailResponse finish(Dispute dispute) {
        // STUB (WP07): records the request in the log only; no payout is actually put on hold.
        payoutHoldPort.holdPayout(dispute.getBookingId(), dispute.getId());
        log.info("Dispute {} raised for booking {}", dispute.getId(), dispute.getBookingId());
        return DisputeMapper.toDetail(dispute);
    }

    @Override
    public DisputePageResponse<DisputeSummaryResponse> getMyDisputes(String userId, int page, int size) {
        return getMyDisputes(userId, Set.of(), page, size);
    }

    @Override
    public DisputePageResponse<DisputeSummaryResponse> getMyDisputes(String userId, Set<DisputeStatus> statuses,
                                                                     int page, int size) {
        Pageable pageable = pageRequest(page, size);
        Page<Dispute> disputes = statuses == null || statuses.isEmpty()
                ? disputeRepository.findVisibleByParty(userId, pageable)
                : disputeRepository.findVisibleByPartyAndStatusIn(userId, statuses, pageable);
        return DisputePageResponse.from(disputes.map(DisputeMapper::toSummary));
    }

    @Override
    public DisputeDetailResponse getDisputeForParty(String userId, String disputeId) {
        Dispute dispute = findDispute(disputeId);
        assertParty(dispute, userId);
        return DisputeMapper.toPartyDetail(dispute, userId, clock.instant());
    }

    @Override
    public DisputeDetailResponse submitResponse(String actorId, Role actorRole, String disputeId,
                                                SubmitDisputeResponseRequest request) {
        Dispute dispute = findDispute(disputeId);
        assertParty(dispute, actorId);
        if (actorId.equals(dispute.getRaisedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_RESPONDING_PARTY",
                    "Only the other party can respond to this dispute");
        }
        if (dispute.getStatus() != DisputeStatus.AWAITING_RESPONSE) {
            throw new ResourceConflictException("INVALID_DISPUTE_STATUS",
                    "This dispute is not waiting for a response");
        }

        Instant now = clock.instant();
        if (!now.isBefore(dispute.getResponseDueAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESPONSE_WINDOW_CLOSED",
                    "The response window for this dispute has closed");
        }

        PartyResponse response = new PartyResponse();
        response.setResponderId(actorId);
        response.setText(request.text().strip());
        response.setEvidence(request.evidence());
        response.setRespondedAt(now);
        dispute.setResponse(response);
        dispute.changeStatus(DisputeStatus.UNDER_REVIEW, actorId, actorRole, null, now);

        Dispute saved;
        try {
            saved = disputeRepository.save(dispute);
        } catch (OptimisticLockingFailureException e) {
            throw new ResourceConflictException("DISPUTE_CONCURRENT_UPDATE",
                    "The dispute was changed by another request. Reload it and try again");
        }

        log.info("Response submitted for dispute {}", saved.getId());
        return DisputeMapper.toDetail(saved);
    }

    @Override
    public DisputePageResponse<DisputeSummaryResponse> getDisputesForAdmin(String status, int page, int size) {
        Pageable pageable = pageRequest(page, size);
        Page<Dispute> disputes = status == null || status.isBlank()
                ? disputeRepository.findAllVisible(pageable)
                : disputeRepository.findVisibleByStatus(parseStatus(status), pageable);
        return DisputePageResponse.from(disputes.map(DisputeMapper::toSummary));
    }

    @Override
    public DisputeDetailResponse getDisputeForAdmin(String disputeId) {
        return DisputeMapper.toDetail(findDispute(disputeId));
    }

    @Override
    public DisputeAiSummaryData getDisputeForAiSummary(String disputeId) {
        return DisputeMapper.toAiSummaryData(findDispute(disputeId), clock.instant());
    }

    /** Short, system-generated text for the booking timeline. The user's description is never copied there. */
    static String bookingTimelineReason(DisputeCategory category) {
        return "Dispute raised: " + category;
    }

    /** Only visible disputes: a PENDING intent record is reported as not found to everyone. */
    private Dispute findDispute(String disputeId) {
        return disputeRepository.findById(disputeId)
                .filter(Dispute::isVisible)
                .orElseThrow(() -> new ResourceNotFoundException("DISPUTE_NOT_FOUND", "Dispute not found"));
    }

    private static void assertParty(Dispute dispute, String userId) {
        if (!dispute.isParty(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_DISPUTE_PARTY",
                    "You are not a party to this dispute");
        }
    }

    private static ResourceConflictException duplicateDispute() {
        return new ResourceConflictException("DISPUTE_ALREADY_EXISTS",
                "A dispute already exists for this booking");
    }

    private static ApiException syncPending() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DISPUTE_SYNC_PENDING",
                "Your dispute was received but is not ready yet. Please try again in a moment");
    }

    private static DisputeStatus parseStatus(String status) {
        try {
            return DisputeStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_FILTER", "Unknown dispute status");
        }
    }

    private static Pageable pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > DisputeRules.MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST",
                    "page must be 0 or more and size must be between 1 and " + DisputeRules.MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size, NEWEST_FIRST);
    }
}

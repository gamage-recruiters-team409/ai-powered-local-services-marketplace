package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeDetailResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputePageResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.DisputeSummaryResponse;
import com.gamagerecruiters.marketplace.modules.dispute.dto.RaiseDisputeRequest;
import com.gamagerecruiters.marketplace.modules.dispute.dto.SubmitDisputeResponseRequest;
import com.gamagerecruiters.marketplace.modules.dispute.mapper.DisputeMapper;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingDisputeStatusPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;
import com.gamagerecruiters.marketplace.modules.dispute.port.DisputeEligibilityPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;
import com.gamagerecruiters.marketplace.modules.dispute.repository.DisputeRepository;

@Service
public class DisputeServiceImpl implements DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeServiceImpl.class);

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final DisputeRepository disputeRepository;
    private final DisputeBookingSyncStore syncStore;
    private final BookingDisputeStatusPort bookingDisputeStatusPort;
    private final DisputeEligibilityPort disputeEligibilityPort;
    private final PayoutHoldPort payoutHoldPort;
    private final Clock clock;

    @Autowired
    public DisputeServiceImpl(DisputeRepository disputeRepository,
                              DisputeBookingSyncStore syncStore,
                              BookingDisputeStatusPort bookingDisputeStatusPort,
                              DisputeEligibilityPort disputeEligibilityPort,
                              PayoutHoldPort payoutHoldPort) {
        this(disputeRepository, syncStore, bookingDisputeStatusPort, disputeEligibilityPort, payoutHoldPort,
                Clock.systemUTC());
    }

    DisputeServiceImpl(DisputeRepository disputeRepository,
                       DisputeBookingSyncStore syncStore,
                       BookingDisputeStatusPort bookingDisputeStatusPort,
                       DisputeEligibilityPort disputeEligibilityPort,
                       PayoutHoldPort payoutHoldPort,
                       Clock clock) {
        this.disputeRepository = disputeRepository;
        this.syncStore = syncStore;
        this.bookingDisputeStatusPort = bookingDisputeStatusPort;
        this.disputeEligibilityPort = disputeEligibilityPort;
        this.payoutHoldPort = payoutHoldPort;
        this.clock = clock;
    }

    /**
     * Raise flow (the Booking module decides eligibility; the dispute module never stays out of step with it):
     * <ol>
     *   <li>BookingFacade.assertDisputeEligible (via {@link DisputeEligibilityPort}): read-only; its errors (401, 403,
     *       404, 409 INVALID_STATUS_TRANSITION / DISPUTE_WINDOW_CLOSED) are returned unchanged. The caller must also
     *       be a party (checked here too, so the reporter and counterparty are always right).</li>
     *   <li>Reject a second dispute for the booking. A PENDING intent record of the same caller is a retry: it is
     *       completed instead of creating another one.</li>
     *   <li>Insert the dispute as PENDING (invisible; the unique active-booking index still blocks duplicates).</li>
     *   <li>Booking not yet DISPUTED: BookingFacade.markDisputed. If it fails, the PENDING record is deleted and the
     *       facade error is returned unchanged. Booking already DISPUTED (and eligible): no booking change needed.</li>
     *   <li>Mark the dispute SYNCED.</li>
     * </ol>
     * If the SYNCED update fails or the process stops in between, the PENDING record stays; a retry of the same
     * request or {@link DisputeBookingSyncReconciler} completes it (booking DISPUTED) or removes it (booking not
     * DISPUTED after a threshold).
     */
    @Override
    public DisputeDetailResponse raiseDispute(String actorId, Role actorRole, RaiseDisputeRequest request) {
        BookingSnapshot booking = disputeEligibilityPort.assertEligible(request.bookingId());

        // Same rule as the Booking module. Kept so the reporter/counterparty are always the booking's own parties.
        if (!booking.isParty(actorId)) {
            throw new AccessDeniedException("Access denied: you are not a participant of this booking");
        }

        Optional<Dispute> existing = disputeRepository.findByActiveBookingKey(booking.bookingId());
        if (existing.isPresent()) {
            Dispute current = existing.get();
            if (!current.isVisible() && actorId.equals(current.getRaisedBy())) {
                return finish(completeIntent(current, booking));
            }
            throw duplicateDispute();
        }

        Dispute intent = insert(newDispute(booking, actorId, actorRole, request));
        return finish(completeIntent(intent, booking));
    }

    /** Moves the booking to DISPUTED unless it already is, then marks the intent record SYNCED. */
    private Dispute completeIntent(Dispute intent, BookingSnapshot booking) {
        if (!booking.alreadyDisputed()) {
            moveBookingOrCompensate(intent);
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

    /** markDisputed as the logged-in user. On any failure the intent record is removed and the error re-thrown as is. */
    private void moveBookingOrCompensate(Dispute intent) {
        try {
            bookingDisputeStatusPort.markDisputed(intent.getBookingId(), bookingTimelineReason(intent.getCategory()));
        } catch (RuntimeException e) {
            compensate(intent);
            throw e;
        }
    }

    private void compensate(Dispute intent) {
        try {
            if (!syncStore.deleteIfPending(intent.getId())) {
                log.warn("event=DISPUTE_INTENT_ALREADY_GONE disputeId={} bookingId={}",
                        intent.getId(), intent.getBookingId());
            }
        } catch (RuntimeException e) {
            // The reconciler removes it later: the booking is not DISPUTED.
            log.error("event=DISPUTE_INTENT_COMPENSATION_FAILED disputeId={} bookingId={} cause={}",
                    intent.getId(), intent.getBookingId(), e.getClass().getSimpleName());
        }
    }

    /** PENDING -> SYNCED. If that cannot be confirmed now, the record stays PENDING for a retry or the reconciler. */
    private Dispute completeSync(Dispute intent) {
        Optional<Dispute> synced;
        try {
            synced = syncStore.markSynced(intent.getId())
                    // Completed meanwhile by another thread or the reconciler.
                    .or(() -> disputeRepository.findById(intent.getId()).filter(Dispute::isVisible));
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_SYNC_PENDING disputeId={} bookingId={} actorId={} cause={}",
                    intent.getId(), intent.getBookingId(), intent.getRaisedBy(), e.getClass().getSimpleName());
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
        Page<Dispute> disputes = disputeRepository.findVisibleByParty(userId, pageRequest(page, size));
        return DisputePageResponse.from(disputes.map(DisputeMapper::toSummary));
    }

    @Override
    public DisputeDetailResponse getDisputeForParty(String userId, String disputeId) {
        Dispute dispute = findDispute(disputeId);
        assertParty(dispute, userId);
        return DisputeMapper.toDetail(dispute);
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
                "An active dispute already exists for this booking");
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

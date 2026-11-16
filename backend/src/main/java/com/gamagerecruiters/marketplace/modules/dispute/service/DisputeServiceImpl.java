package com.gamagerecruiters.marketplace.modules.dispute.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

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
import com.gamagerecruiters.marketplace.modules.dispute.domain.Dispute;
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
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingLookupPort;
import com.gamagerecruiters.marketplace.modules.dispute.port.BookingSnapshot;
import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;
import com.gamagerecruiters.marketplace.modules.dispute.repository.DisputeRepository;

@Service
public class DisputeServiceImpl implements DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeServiceImpl.class);

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final DisputeRepository disputeRepository;
    private final BookingLookupPort bookingLookupPort;
    private final BookingDisputeStatusPort bookingDisputeStatusPort;
    private final PayoutHoldPort payoutHoldPort;
    private final Clock clock;

    @Autowired
    public DisputeServiceImpl(DisputeRepository disputeRepository,
                              BookingLookupPort bookingLookupPort,
                              BookingDisputeStatusPort bookingDisputeStatusPort,
                              PayoutHoldPort payoutHoldPort) {
        this(disputeRepository, bookingLookupPort, bookingDisputeStatusPort, payoutHoldPort, Clock.systemUTC());
    }

    DisputeServiceImpl(DisputeRepository disputeRepository,
                       BookingLookupPort bookingLookupPort,
                       BookingDisputeStatusPort bookingDisputeStatusPort,
                       PayoutHoldPort payoutHoldPort,
                       Clock clock) {
        this.disputeRepository = disputeRepository;
        this.bookingLookupPort = bookingLookupPort;
        this.bookingDisputeStatusPort = bookingDisputeStatusPort;
        this.payoutHoldPort = payoutHoldPort;
        this.clock = clock;
    }

    /**
     * Order: look up the booking, check the caller is a party, reject a duplicate active dispute, move the
     * booking to DISPUTED (skipped when it already is), then save the dispute with its first history entry.
     *
     * <p>The Booking module decides whether the booking may be disputed (status and the 72-hour window after
     * completion, BR-008), so the booking is changed before the dispute is saved: a dispute is never stored for
     * a booking the Booking module refused. If saving then fails, the booking change is not undone; a structured
     * ERROR is logged with ids only and 503 DISPUTE_SAVE_FAILED is returned. Retrying is safe: the booking is
     * then already DISPUTED, so the retry skips the booking call and only saves the dispute.</p>
     */
    @Override
    public DisputeDetailResponse raiseDispute(String actorId, Role actorRole, RaiseDisputeRequest request) {
        // 404 BOOKING_NOT_FOUND comes from the Booking module unchanged.
        BookingSnapshot booking = bookingLookupPort.getBooking(request.bookingId());

        // Checked here (same rule and error as the Booking module) because the booking call is skipped when the
        // booking is already DISPUTED, and so a non-party never learns whether a dispute exists.
        if (!booking.isParty(actorId)) {
            throw new AccessDeniedException("Access denied: you are not a participant of this booking");
        }

        if (disputeRepository.existsByActiveBookingKey(booking.bookingId())) {
            throw duplicateDispute();
        }

        // Errors from the Booking module (401, 403, 409 ...) are passed through unchanged.
        boolean bookingMovedHere = false;
        if (!booking.alreadyDisputed()) {
            bookingDisputeStatusPort.markDisputed(booking.bookingId(), bookingTimelineReason(request));
            bookingMovedHere = true;
        }

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
        dispute.changeStatus(DisputeStatus.AWAITING_RESPONSE, actorId, actorRole, null, now);

        Dispute saved;
        try {
            saved = disputeRepository.save(dispute);
        } catch (DuplicateKeyException e) {
            // A concurrent request created the active dispute for this booking first (unique index); the
            // booking is DISPUTED and has its dispute, so nothing needs reconciling.
            throw duplicateDispute();
        } catch (RuntimeException e) {
            log.error("event=DISPUTE_SAVE_FAILED bookingId={} actorId={} bookingMovedToDisputed={} cause={}",
                    booking.bookingId(), actorId, bookingMovedHere, e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DISPUTE_SAVE_FAILED",
                    "The dispute could not be saved. Please try again");
        }

        // TODO(WP07): still a stub; the payout is not actually put on hold yet.
        payoutHoldPort.holdPayout(saved.getBookingId(), saved.getId());

        log.info("Dispute {} raised for booking {}", saved.getId(), saved.getBookingId());
        return DisputeMapper.toDetail(saved);
    }

    @Override
    public DisputePageResponse<DisputeSummaryResponse> getMyDisputes(String userId, int page, int size) {
        Page<Dispute> disputes = disputeRepository.findByParty(userId, pageRequest(page, size));
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
                ? disputeRepository.findAll(pageable)
                : disputeRepository.findByStatus(parseStatus(status), pageable);
        return DisputePageResponse.from(disputes.map(DisputeMapper::toSummary));
    }

    @Override
    public DisputeDetailResponse getDisputeForAdmin(String disputeId) {
        return DisputeMapper.toDetail(findDispute(disputeId));
    }

    /** Short, system-generated text for the booking timeline. The user's description is never copied there. */
    static String bookingTimelineReason(RaiseDisputeRequest request) {
        return "Dispute raised: " + request.category();
    }

    private Dispute findDispute(String disputeId) {
        return disputeRepository.findById(disputeId)
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

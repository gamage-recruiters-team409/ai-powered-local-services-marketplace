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

    @Override
    public DisputeDetailResponse raiseDispute(String actorId, Role actorRole, RaiseDisputeRequest request) {
        BookingSnapshot booking = bookingLookupPort.findBooking(request.bookingId())
                .orElseThrow(() -> new ResourceNotFoundException("BOOKING_NOT_FOUND", "Booking not found"));

        if (!isCallerSideOfBooking(actorId, actorRole, booking)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_BOOKING_PARTY",
                    "You can only raise a dispute for your own booking");
        }

        Instant now = clock.instant();
        checkRaiseWindow(booking, now);

        if (disputeRepository.existsByActiveBookingKey(booking.bookingId())) {
            throw duplicateDispute();
        }

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
            // A concurrent request created an active dispute for the same booking first (unique index).
            throw duplicateDispute();
        }

        // Other modules are changed only through their ports. If either call fails, the error is returned
        // and the dispute stays recorded, so it can be reconciled (see open question on transactions).
        bookingDisputeStatusPort.markDisputed(saved.getBookingId(), saved.getId());
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

    /** The caller must be on the booking side that matches their role. */
    private static boolean isCallerSideOfBooking(String actorId, Role actorRole, BookingSnapshot booking) {
        if (actorId == null) {
            return false;
        }
        if (actorRole == Role.CUSTOMER) {
            return actorId.equals(booking.customerId());
        }
        if (actorRole == Role.PROVIDER) {
            return actorId.equals(booking.providerId());
        }
        return false;
    }

    /** Disputes can be raised from the end of the booking slot until RAISE_WINDOW after it. */
    private static void checkRaiseWindow(BookingSnapshot booking, Instant now) {
        Instant slotEnd = booking.slotEndAt();
        if (slotEnd == null || now.isBefore(slotEnd)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DISPUTE_WINDOW_NOT_OPEN",
                    "A dispute can be raised only after the booking slot has ended");
        }
        if (now.isAfter(slotEnd.plus(DisputeRules.RAISE_WINDOW))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DISPUTE_WINDOW_CLOSED",
                    "The 72-hour window to raise a dispute for this booking has closed");
        }
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

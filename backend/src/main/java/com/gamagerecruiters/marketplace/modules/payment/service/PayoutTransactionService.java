package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.completion.service.CompletionLookup;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payout;
import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutAllocation;
import com.gamagerecruiters.marketplace.modules.payment.domain.PayoutStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.PaymentStatus;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PayoutAllocationRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.PayoutRepository;

/** Atomically reserves, settles, and releases provider manual payouts. */
@Service
public class PayoutTransactionService {

    private final PayoutRepository payoutRepository;
    private final PayoutAllocationRepository allocationRepository;
    private final EarningsLedgerRepository ledgerRepository;
    private final EarningsLedgerService earningsLedgerService;
    private final BookingPayoutAllocationCalculator allocationCalculator;
    private final BookingFinancialSnapshotService snapshotService;
    private final DisputePayoutHoldRepository holdRepository;
    private final BookingFacade bookingFacade;
    private final CompletionLookup completionLookup;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration payoutMaturityDuration;

    @Autowired
    public PayoutTransactionService(
            PayoutRepository payoutRepository,
            PayoutAllocationRepository allocationRepository,
            EarningsLedgerRepository ledgerRepository,
            EarningsLedgerService earningsLedgerService,
            BookingPayoutAllocationCalculator allocationCalculator,
            BookingFinancialSnapshotService snapshotService,
            DisputePayoutHoldRepository holdRepository,
            BookingFacade bookingFacade,
            CompletionLookup completionLookup,
            PlatformTransactionManager transactionManager) {
        this(payoutRepository, allocationRepository, ledgerRepository,
                earningsLedgerService, allocationCalculator, snapshotService,
                holdRepository, bookingFacade, completionLookup,
                transactionManager, Clock.systemUTC(), Duration.ofHours(72));
    }

    PayoutTransactionService(
            PayoutRepository payoutRepository,
            PayoutAllocationRepository allocationRepository,
            EarningsLedgerRepository ledgerRepository,
            EarningsLedgerService earningsLedgerService,
            BookingPayoutAllocationCalculator allocationCalculator,
            BookingFinancialSnapshotService snapshotService,
            DisputePayoutHoldRepository holdRepository,
            BookingFacade bookingFacade,
            CompletionLookup completionLookup,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Duration payoutMaturityDuration) {

        this.payoutRepository = Objects.requireNonNull(payoutRepository);
        this.allocationRepository = Objects.requireNonNull(allocationRepository);
        this.ledgerRepository = Objects.requireNonNull(ledgerRepository);
        this.earningsLedgerService = Objects.requireNonNull(earningsLedgerService);
        this.allocationCalculator = Objects.requireNonNull(allocationCalculator);
        this.snapshotService = Objects.requireNonNull(snapshotService);
        this.holdRepository = Objects.requireNonNull(holdRepository);
        this.bookingFacade = Objects.requireNonNull(bookingFacade);
        this.completionLookup = Objects.requireNonNull(completionLookup);
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
        this.payoutMaturityDuration = Objects.requireNonNull(payoutMaturityDuration);
    }

    /**
     * Creates and reserves a payout atomically, or returns an identical existing
     * request.
     */
    public Payout reserve(Payout requested) {
        Objects.requireNonNull(requested, "Payout is required");

        if (requested.getStatus() != PayoutStatus.PENDING) {
            throw new IllegalStateException("Only PENDING payouts can be reserved");
        }

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            var existing = payoutRepository.findByRequestKey(requested.getRequestKey());
            if (existing.isPresent()) {
                requireMatchingPayout(existing.get(), requested);
                if (existing.get().getStatus() == PayoutStatus.PENDING
                        && !allocationRepository.findByPayoutId(existing.get().getId()).isEmpty()) {
                    return existing.get();
                }
                if (existing.get().getStatus() != PayoutStatus.PENDING) {
                    return existing.get();
                }
                return existing.get();
            }

            Payout payout = payoutRepository.save(requested);
            requirePersistedId(payout);

            List<EarningsLedgerEntry> providerHistory = ledgerRepository
                    .findByProviderIdOrderByCreatedAtDesc(payout.getProviderId());

            if (providerHistory == null) {
                throw new IllegalStateException("Provider earnings history is unavailable");
            }

            Map<String, List<EarningsLedgerEntry>> groups = new LinkedHashMap<>();
            for (EarningsLedgerEntry entry : providerHistory) {
                if (entry == null) {
                    throw new IllegalStateException("Ledger history contains a null entry");
                }
                if (!payout.getProviderId().equals(entry.getProviderId())
                        || !Payout.SUPPORTED_CURRENCY.equals(entry.getCurrency())) {
                    continue;
                }
                String key = requireText(entry.getBookingId(), "Ledger booking ID")
                        + "|" + requireText(entry.getPaymentId(), "Ledger payment ID");
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
            }

            List<PayoutAllocation> allocations = new ArrayList<>();
            BigDecimal remaining = payout.getAmount();

            List<String> keys = new ArrayList<>(groups.keySet());
            keys.sort(String::compareTo);

            for (String key : keys) {
                if (remaining.signum() <= 0) {
                    break;
                }

                String[] ids = key.split("\\|", -1);
                String bookingId = ids[0];
                String paymentId = ids[1];

                BookingFinancialSnapshotService.FinancialSnapshot snapshot = snapshotService.getSnapshot(bookingId);

                if (!payout.getProviderId().equals(snapshot.providerId())
                        || !payout.getCurrency().equals(snapshot.currency())
                        || !paymentId.equals(snapshot.paymentId())) {
                    throw new IllegalStateException("Payout allocation ownership mismatch");
                }
                if (snapshot.paymentStatus() != PaymentStatus.CAPTURED
                        && snapshot.paymentStatus() != PaymentStatus.RELEASED) {
                    throw new IllegalStateException("Payout allocation payment is not eligible");
                }
                if (snapshot.refundedAmount().signum() != 0) {
                    throw new IllegalStateException("Refunded earnings cannot be paid out");
                }

                var participants = bookingFacade.getBookingParticipants(bookingId);
                if (participants == null
                        || !bookingId.equals(participants.bookingId())
                        || participants.status() != BookingStatus.COMPLETED
                        || !payout.getProviderId().equals(participants.providerId())) {
                    throw new IllegalStateException("Payout allocation booking is not completed and eligible");
                }

                var confirmedAt = completionLookup.findConfirmedAt(bookingId);
                if (confirmedAt.isEmpty()) {
                    throw new IllegalStateException("Payout allocation lacks verified completion evidence");
                }

                requireNoActiveDisputeHold(bookingId);
                requireApprovedPayoutMaturity(bookingId, confirmedAt.get());

                BigDecimal available = allocationCalculator.calculateAvailable(groups.get(key));
                if (available.signum() <= 0) {
                    continue;
                }

                BigDecimal allocated = remaining.compareTo(available) <= 0
                        ? remaining
                        : available;

                allocations.add(new PayoutAllocation(
                        payout.getId(), payout.getProviderId(), paymentId,
                        bookingId, allocated, payout.getCurrency()));
                remaining = remaining.subtract(allocated);
            }

            if (remaining.signum() != 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "INSUFFICIENT_ELIGIBLE_EARNINGS",
                        "Insufficient eligible booking earnings for payout");
            }

            for (PayoutAllocation allocation : allocations) {
                allocationRepository.save(allocation);

                EarningsLedgerEntry reserve = payoutEvent(
                        payout,
                        allocation,
                        EarningsLedgerEventType.PAYOUT_RESERVE,
                        "PAYOUT_RESERVE:" + payout.getId() + ":"
                                + allocation.getPaymentId() + ":" + allocation.getBookingId(),
                        allocation.getAmount());
                earningsLedgerService.appendEvent(reserve);
            }

            return payoutRepository.save(payout);
        }));
    }

    /** Moves a reserved payout into admin processing. */
    public Payout markProcessing(String payoutId, String adminId) {
        requireText(payoutId, "Payout ID");
        requireText(adminId, "Admin ID");

        return transactionTemplate.execute(transactionStatus -> {
            Payout payout = requirePayout(payoutId);
            if (payout.getStatus() == PayoutStatus.PROCESSING) {
                return payout;
            }
            requireAllocations(payout);
            payout.markProcessing(adminId);
            return payoutRepository.save(payout);
        });
    }

    /**
     * Marks a payout PAID only after explicit admin confirmation and a transfer
     * reference.
     */
    public Payout markPaid(String payoutId, String adminId, String transferReference) {
        requireText(payoutId, "Payout ID");
        requireText(adminId, "Admin ID");

        return transactionTemplate.execute(transactionStatus -> {
            Payout payout = requirePayout(payoutId);
            requireAllocations(payout);

            if (payout.getStatus() == PayoutStatus.PAID) {
                if (!adminId.equals(payout.getProcessedBy())
                        || !requireText(transferReference, "Bank transfer reference")
                                .equals(payout.getTransferReference())) {
                    throw new IllegalStateException("Paid payout confirmation does not match");
                }
                return payout;
            }

            payout.markPaid(adminId, transferReference);

            for (PayoutAllocation allocation : allocationRepository.findByPayoutId(payoutId)) {
                earningsLedgerService.appendEvent(payoutEvent(
                        payout,
                        allocation,
                        EarningsLedgerEventType.PAYOUT_SETTLE,
                        "PAYOUT_SETTLE:" + payout.getId() + ":"
                                + allocation.getPaymentId() + ":" + allocation.getBookingId(),
                        allocation.getAmount()));
            }

            return payoutRepository.save(payout);
        });
    }

    /** Releases all reservations after an explicit processing failure. */
    public Payout markFailed(String payoutId, String adminId, String reason) {
        requireText(payoutId, "Payout ID");
        requireText(adminId, "Admin ID");

        return transactionTemplate.execute(transactionStatus -> {
            Payout payout = requirePayout(payoutId);
            requireAllocations(payout);

            if (payout.getStatus() == PayoutStatus.FAILED) {
                if (!adminId.equals(payout.getProcessedBy())
                        || !requireText(reason, "Failure reason")
                                .equals(payout.getFailureReason())) {
                    throw new IllegalStateException("Failed payout recovery does not match");
                }
                return payout;
            }

            payout.markFailed(adminId, reason);

            for (PayoutAllocation allocation : allocationRepository.findByPayoutId(payoutId)) {
                earningsLedgerService.appendEvent(payoutEvent(
                        payout,
                        allocation,
                        EarningsLedgerEventType.PAYOUT_RELEASE,
                        "PAYOUT_RELEASE:" + payout.getId() + ":"
                                + allocation.getPaymentId() + ":" + allocation.getBookingId(),
                        allocation.getAmount()));
            }

            return payoutRepository.save(payout);
        });
    }

    private void requireNoActiveDisputeHold(String bookingId) {
        List<DisputePayoutHold> holds = holdRepository.findByBookingId(bookingId);
        if (holds == null) {
            throw new IllegalStateException("Dispute hold history is unavailable");
        }
        for (DisputePayoutHold hold : holds) {
            if (hold != null && (hold.getStatus() == DisputePayoutHold.HoldStatus.PENDING
                    || hold.getStatus() == DisputePayoutHold.HoldStatus.APPLIED)) {
                throw new IllegalStateException("Payout blocked by an active dispute hold");
            }
        }
    }

    private void requireApprovedPayoutMaturity(String bookingId, Instant confirmedAt) {
        Objects.requireNonNull(confirmedAt, "Completion confirmation timestamp is required");

        Instant now = clock.instant();
        if (confirmedAt.isAfter(now)) {
            throw new IllegalStateException(
                    "Payout blocked: completion confirmation is in the future for booking "
                            + bookingId);
        }

        Instant maturityBoundary = confirmedAt.plus(payoutMaturityDuration);
        if (now.isBefore(maturityBoundary)) {
            throw new IllegalStateException(
                    "Payout blocked: earnings mature only at or after "
                            + maturityBoundary + " for booking " + bookingId);
        }
    }

    private EarningsLedgerEntry payoutEvent(
            Payout payout,
            PayoutAllocation allocation,
            EarningsLedgerEventType eventType,
            String eventKey,
            BigDecimal amount) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();
        entry.setEventKey(eventKey);
        entry.setPaymentId(allocation.getPaymentId());
        entry.setBookingId(allocation.getBookingId());
        entry.setProviderId(payout.getProviderId());
        entry.setEventType(eventType);
        entry.setAmount(amount);
        entry.setCurrency(payout.getCurrency());
        entry.setCreatedAt(Instant.now());
        return entry;
    }

    private Payout requirePayout(String payoutId) {
        return payoutRepository.findById(payoutId)
                .orElseThrow(() -> new IllegalStateException("Payout was not found"));
    }

    private void requireAllocations(Payout payout) {
        List<PayoutAllocation> allocations = allocationRepository.findByPayoutId(payout.getId());
        if (allocations == null || allocations.isEmpty()) {
            throw new IllegalStateException("Payout has no financial allocations");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (PayoutAllocation allocation : allocations) {
            if (allocation == null
                    || !payout.getProviderId().equals(allocation.getProviderId())
                    || !payout.getCurrency().equals(allocation.getCurrency())) {
                throw new IllegalStateException("Payout allocation ownership mismatch");
            }
            total = total.add(allocation.getAmount());
        }
        if (total.compareTo(payout.getAmount()) != 0) {
            throw new IllegalStateException("Payout allocation total mismatch");
        }
    }

    private void requireMatchingPayout(Payout existing, Payout requested) {
        if (!existing.getProviderId().equals(requested.getProviderId())
                || existing.getAmount().compareTo(requested.getAmount()) != 0
                || !existing.getCurrency().equals(requested.getCurrency())) {
            throw new IllegalStateException("Payout request key belongs to different financial data");
        }
    }

    private static void requirePersistedId(Payout payout) {
        if (payout == null || payout.getId() == null || payout.getId().isBlank()) {
            throw new IllegalStateException("Payout persistence did not assign an ID");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}

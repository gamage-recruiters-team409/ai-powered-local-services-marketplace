
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold.HoldStatus;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEntry;
import com.gamagerecruiters.marketplace.modules.payment.domain.EarningsLedgerEventType;
import com.gamagerecruiters.marketplace.modules.payment.repository.DisputePayoutHoldRepository;
import com.gamagerecruiters.marketplace.modules.payment.repository.EarningsLedgerRepository;

/**
 * Coordinates atomic financial reservations for disputed bookings.
 *
 * <p>
 * A successful hold transfers available provider earnings into
 * held earnings and marks the dispute hold as APPLIED within the
 * same MongoDB transaction.
 * </p>
 *
 * <p>
 * The existing EarningsLedgerTransactionService is reused to
 * preserve ledger sequencing and provider balance validation.
 * </p>
 */
@Service
public class DisputePayoutHoldTransactionService {

    private static final String HOLD_EVENT_PREFIX = "DISPUTE_HOLD:";

    private final DisputePayoutHoldRepository holdRepository;
    private final EarningsLedgerRepository ledgerRepository;
    private final EarningsBalanceCalculator balanceCalculator;
    private final EarningsLedgerTransactionService ledgerTransactionService;
    private final TransactionTemplate transactionTemplate;

    public DisputePayoutHoldTransactionService(
            DisputePayoutHoldRepository holdRepository,
            EarningsLedgerRepository ledgerRepository,
            EarningsBalanceCalculator balanceCalculator,
            EarningsLedgerTransactionService ledgerTransactionService,
            PlatformTransactionManager transactionManager) {

        this.holdRepository = Objects.requireNonNull(
                holdRepository, "Hold repository is required");

        this.ledgerRepository = Objects.requireNonNull(
                ledgerRepository, "Ledger repository is required");

        this.balanceCalculator = Objects.requireNonNull(
                balanceCalculator, "Balance calculator is required");

        this.ledgerTransactionService = Objects.requireNonNull(
                ledgerTransactionService,
                "Ledger transaction service is required");

        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(
                        transactionManager,
                        "Transaction manager is required"));
    }

    /**
     * Validates a pending dispute hold without reserving funds.
     *
     * @param disputeId unique dispute identifier
     * @return validated pending hold
     */
    public DisputePayoutHold validatePendingHold(String disputeId) {

        requireDisputeId(disputeId);

        return Objects.requireNonNull(
                transactionTemplate.execute(status -> {

                    DisputePayoutHold hold = requireHold(disputeId);

                    if (hold.getStatus() != HoldStatus.PENDING) {
                        throw new IllegalStateException(
                                "Dispute payout hold is not pending");
                    }

                    validateBookingAvailability(hold);
                    validateNoConflictingHold(hold);

                    return hold;
                }));
    }

    /**
     * Atomically applies a booking-specific dispute payout hold.
     *
     * <p>
     * The ledger entry, provider earnings state and hold status
     * participate in the same MongoDB transaction.
     * Repeated requests for an already applied matching hold
     * return the existing hold without creating another event.
     * </p>
     *
     * @param disputeId unique dispute identifier
     * @return applied dispute payout hold
     */
    public DisputePayoutHold applyHold(String disputeId) {

        requireDisputeId(disputeId);

        return Objects.requireNonNull(
                transactionTemplate.execute(status -> {

                    DisputePayoutHold hold = requireHold(disputeId);

                    String eventKey = HOLD_EVENT_PREFIX + disputeId;

                    if (hold.getStatus() == HoldStatus.APPLIED) {
                        requireMatchingCommittedEvent(hold, eventKey);
                        return hold;
                    }

                    if (hold.getStatus() != HoldStatus.PENDING) {
                        throw new IllegalStateException(
                                "Dispute payout hold cannot be applied "
                                        + "from status " + hold.getStatus());
                    }

                    // Reject inconsistent records instead of silently
                    // creating another financial reservation.
                    if (ledgerRepository.existsByEventKey(eventKey) || ledgerRepository.existsByEventKey(eventKey + ":AVAILABLE")) {
                        throw new IllegalStateException(
                                "Hold ledger event already exists "
                                        + "but hold is not APPLIED");
                    }

                    validateNoConflictingHold(hold);

                                        // Reserve booking-specific pending and available earnings.
                    // Both events join this MongoDB transaction.
                    HoldAllocation allocation = determineHoldAllocation(hold);

                    if (allocation.pending().signum() > 0) {
                        EarningsLedgerEntry pendingEntry = createHoldEvent(
                                hold, eventKey,
                                EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING,
                                allocation.pending());

                        ledgerTransactionService.appendAtomically(pendingEntry);
                    }

                    if (allocation.available().signum() > 0) {
                        String availableKey = allocation.pending().signum() > 0
                                ? eventKey + ":AVAILABLE"
                                : eventKey;

                        EarningsLedgerEntry availableEntry = createHoldEvent(
                                hold, availableKey,
                                EarningsLedgerEventType.EARNING_HOLD,
                                allocation.available());

                        ledgerTransactionService.appendAtomically(availableEntry);
                    }

                    // This status change must commit together with
                    // the ledger and provider balance updates.
                    hold.markApplied();

                    return holdRepository.save(hold);
                }));
    }

    /**
     * Loads the dispute hold or rejects an unknown dispute.
     */
    private DisputePayoutHold requireHold(String disputeId) {

        return holdRepository.findByDisputeId(disputeId)
                .orElseThrow(() -> new IllegalStateException(
                        "Dispute payout hold was not found"));
    }

    /**
     * Ensures that another active dispute has not already
     * reserved or requested the same booking's earnings.
     */
    private void validateNoConflictingHold(
            DisputePayoutHold requested) {

        List<DisputePayoutHold> bookingHolds = holdRepository.findByBookingId(
                requested.getBookingId());

        if (bookingHolds == null) {
            throw new IllegalStateException(
                    "Booking hold history is unavailable");
        }

        for (DisputePayoutHold existing : bookingHolds) {

            if (existing == null) {
                throw new IllegalStateException(
                        "Invalid booking hold history");
            }

            if (requested.getDisputeId().equals(
                    existing.getDisputeId())) {
                continue;
            }

            if (existing.getStatus() == HoldStatus.PENDING
                    || existing.getStatus() == HoldStatus.APPLIED
                    || existing.getStatus() == HoldStatus.SETTLING) {

                throw new IllegalStateException(
                        "Another active payout hold exists "
                                + "for this booking");
            }
        }
    }

    /**
     * Validates the available earnings attributable to the
     * disputed booking and its original payment.
     */
    private void validateBookingAvailability(
            DisputePayoutHold hold) {
        determineHoldAllocation(hold);
    }

    /**
     * Calculates booking-specific pending and available reservations.
     * The combined reservation must cover the entire hold amount.
     */
    private HoldAllocation determineHoldAllocation(
            DisputePayoutHold hold) {

        List<EarningsLedgerEntry> entries =
                ledgerRepository.findByBookingIdOrderByCreatedAtDesc(
                        hold.getBookingId());

        if (entries == null || entries.isEmpty()) {
            throw new IllegalStateException(
                    "No earnings ledger history for disputed booking");
        }

        for (EarningsLedgerEntry entry : entries) {
            if (entry == null
                    || !hold.getPaymentId().equals(entry.getPaymentId())
                    || !hold.getBookingId().equals(entry.getBookingId())
                    || !hold.getProviderId().equals(entry.getProviderId())
                    || !hold.getCurrency().equals(entry.getCurrency())) {
                throw new IllegalStateException(
                        "Booking ledger contains conflicting financial ownership");
            }
        }

        EarningsBalanceCalculator.EarningsBalance balance =
                balanceCalculator.calculate(entries);

        BigDecimal pending = balance.pending();
        BigDecimal available = balance.available();
        BigDecimal required = hold.getAmount();

        if (pending == null || available == null || required == null
                || pending.signum() < 0 || available.signum() < 0
                || required.signum() <= 0) {
            throw new IllegalStateException(
                    "Invalid booking earnings balances or hold amount");
        }

        if (pending.add(available).compareTo(required) < 0) {
            throw new IllegalStateException(
                    "Insufficient booking-specific pending and available earnings");
        }

        BigDecimal fromPending = pending.min(required);
        BigDecimal fromAvailable = required.subtract(fromPending);

        return new HoldAllocation(fromPending, fromAvailable);
    }

    private record HoldAllocation(
            BigDecimal pending,
            BigDecimal available) {
    }


    /**
     * Builds a deterministic, validated earnings hold event.
     */
    private EarningsLedgerEntry createHoldEvent(
            DisputePayoutHold hold,
            String eventKey,
            EarningsLedgerEventType eventType,
            BigDecimal amount) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey(eventKey);
        entry.setPaymentId(hold.getPaymentId());
        entry.setBookingId(hold.getBookingId());
        entry.setProviderId(hold.getProviderId());
        entry.setEventType(eventType);
        entry.setAmount(amount);
        entry.setCurrency(hold.getCurrency());
        entry.setCreatedAt(Instant.now());

        entry.validate();

        return entry;
    }

    /**
     * Confirms that an APPLIED hold has the matching
     * committed ledger event before treating it as idempotent.
     */
    private void requireMatchingCommittedEvent(
            DisputePayoutHold hold,
            String eventKey) {

        EarningsLedgerEntry primary = ledgerRepository.findByEventKey(eventKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Applied hold has no primary committed ledger event"));

        validateCommittedHoldEntry(hold, primary, eventKey);

        String availableKey = eventKey + ":AVAILABLE";
        var secondary = ledgerRepository.findByEventKey(availableKey);

        BigDecimal total = primary.getAmount();

        if (secondary.isPresent()) {
            EarningsLedgerEntry additional = secondary.get();
            validateCommittedHoldEntry(hold, additional, availableKey);

            if (primary.getEventType()
                    != EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING
                    || additional.getEventType()
                    != EarningsLedgerEventType.EARNING_HOLD) {
                throw new IllegalStateException(
                        "Invalid split hold event ordering");
            }

            total = total.add(additional.getAmount());
        }

        if (total.compareTo(hold.getAmount()) != 0) {
            throw new IllegalStateException(
                    "Applied hold amount does not match committed ledger events");
        }
    }

    private void validateCommittedHoldEntry(
            DisputePayoutHold hold,
            EarningsLedgerEntry recorded,
            String expectedKey) {

        boolean matches = Objects.equals(expectedKey, recorded.getEventKey())
                && Objects.equals(hold.getPaymentId(), recorded.getPaymentId())
                && Objects.equals(hold.getBookingId(), recorded.getBookingId())
                && Objects.equals(hold.getProviderId(), recorded.getProviderId())
                && Objects.equals(hold.getCurrency(), recorded.getCurrency())
                && (recorded.getEventType() == EarningsLedgerEventType.EARNING_HOLD
                        || recorded.getEventType()
                        == EarningsLedgerEventType.EARNING_HOLD_FROM_PENDING)
                && recorded.getAmount() != null
                && recorded.getAmount().signum() > 0;

        if (!matches) {
            throw new IllegalStateException(
                    "Applied hold does not match its committed ledger event");
        }
    }


    /**
     * Rejects missing dispute identifiers.
     */
    /**
     * Atomically returns reserved earnings to their original balance sources.
     * A repeated release verifies the committed ledger events without
     * creating duplicate financial movements.
     */
    public DisputePayoutHold releaseHold(String disputeId) {
        requireDisputeId(disputeId);

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            DisputePayoutHold hold = requireHold(disputeId);

            String holdKey = HOLD_EVENT_PREFIX + disputeId;
            String availableHoldKey = holdKey + ":AVAILABLE";
            String releaseKey = "DISPUTE_RELEASE:" + disputeId;
            String availableReleaseKey = releaseKey + ":AVAILABLE";

            if (hold.getStatus() != HoldStatus.APPLIED
                    && hold.getStatus() != HoldStatus.RELEASED) {
                throw new IllegalStateException(
                        "Only applied holds can be released");
            }

            // Verify the original reservation before moving any money.
            requireMatchingCommittedEvent(hold, holdKey);

            EarningsLedgerEntry primary = ledgerRepository
                    .findByEventKey(holdKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Original hold ledger event missing"));

            var secondary = ledgerRepository.findByEventKey(availableHoldKey);

            if (hold.getStatus() == HoldStatus.RELEASED) {
                verifyReleaseEvent(
                        hold, releaseKey, primary.getEventType(),
                        primary.getAmount());

                if (secondary.isPresent()) {
                    verifyReleaseEvent(
                            hold, availableReleaseKey,
                            secondary.get().getEventType(),
                            secondary.get().getAmount());
                } else if (ledgerRepository.existsByEventKey(
                        availableReleaseKey)) {
                    throw new IllegalStateException(
                            "Unexpected secondary release event");
                }

                return hold;
            }

            if (ledgerRepository.existsByEventKey(releaseKey)
                    || ledgerRepository.existsByEventKey(availableReleaseKey)) {
                throw new IllegalStateException(
                        "Release ledger event already exists for an applied hold");
            }

            appendReleaseEvent(hold, primary, releaseKey);

            if (secondary.isPresent()) {
                appendReleaseEvent(
                        hold, secondary.get(), availableReleaseKey);
            }

            hold.markReleased();
            return holdRepository.save(hold);
        }));
    }

    /**
     * Appends a source-preserving release within the outer transaction.
     */
    private void appendReleaseEvent(
            DisputePayoutHold hold,
            EarningsLedgerEntry original,
            String eventKey) {

        EarningsLedgerEventType releaseType =
                releaseTypeFor(original.getEventType());

        EarningsLedgerEntry release = createHoldEvent(
                hold, eventKey, releaseType, original.getAmount());

        ledgerTransactionService.appendAtomically(release);
    }

    /**
     * Maps a reservation to the balance from which it originated.
     */
    private EarningsLedgerEventType releaseTypeFor(
            EarningsLedgerEventType originalType) {

        return switch (originalType) {
            case EARNING_HOLD_FROM_PENDING ->
                    EarningsLedgerEventType.EARNING_RELEASE_TO_PENDING;
            case EARNING_HOLD ->
                    EarningsLedgerEventType.EARNING_RELEASE;
            default -> throw new IllegalStateException(
                    "Unsupported original hold event type");
        };
    }

    /**
     * Verifies the persisted release on an idempotent retry.
     */
    private void verifyReleaseEvent(
            DisputePayoutHold hold,
            String releaseKey,
            EarningsLedgerEventType originalType,
            BigDecimal expectedAmount) {

        EarningsLedgerEntry recorded = ledgerRepository
                .findByEventKey(releaseKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Released hold has no committed release event"));

        boolean matches = Objects.equals(releaseKey, recorded.getEventKey())
                && Objects.equals(hold.getPaymentId(), recorded.getPaymentId())
                && Objects.equals(hold.getBookingId(), recorded.getBookingId())
                && Objects.equals(hold.getProviderId(), recorded.getProviderId())
                && Objects.equals(hold.getCurrency(), recorded.getCurrency())
                && recorded.getEventType() == releaseTypeFor(originalType)
                && recorded.getAmount() != null
                && recorded.getAmount().compareTo(expectedAmount) == 0;

        if (!matches) {
            throw new IllegalStateException(
                    "Committed release does not match original reservation");
        }
    }
    /**
     * Claims an applied dispute hold for refund settlement.
     *
     * This method must participate in the caller's existing MongoDB
     * refund reservation transaction. It must not commit independently.
     */
    public DisputePayoutHold claimHoldForRefund(
            DisputePayoutHold expectedHold) {

        Objects.requireNonNull(expectedHold, "Expected hold is required");

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Refund hold claim requires an active transaction");
        }

        String disputeId = expectedHold.getDisputeId();
        requireDisputeId(disputeId);

        DisputePayoutHold hold = requireHold(disputeId);

        if (hold.getStatus() != HoldStatus.APPLIED
                || !Objects.equals(hold.getId(), expectedHold.getId())
                || !Objects.equals(hold.getPaymentId(), expectedHold.getPaymentId())
                || !Objects.equals(hold.getBookingId(), expectedHold.getBookingId())
                || !Objects.equals(hold.getProviderId(), expectedHold.getProviderId())
                || !Objects.equals(hold.getCurrency(), expectedHold.getCurrency())
                || hold.getAmount().compareTo(expectedHold.getAmount()) != 0) {

            throw new IllegalStateException(
                    "Dispute hold changed before refund settlement");
        }

        validateNoConflictingHold(hold);

        requireMatchingCommittedEvent(
                hold, HOLD_EVENT_PREFIX + disputeId);

        String releaseKey = "DISPUTE_RELEASE:" + disputeId;

        if (ledgerRepository.existsByEventKey(releaseKey)
                || ledgerRepository.existsByEventKey(releaseKey + ":AVAILABLE")) {

            throw new IllegalStateException(
                    "Dispute hold already has release events");
        }

        hold.markSettling();

        return holdRepository.save(hold);
    }

    private static void requireDisputeId(String disputeId) {

        if (disputeId == null || disputeId.isBlank()) {
            throw new IllegalArgumentException(
                    "Dispute ID is required");
        }
    }
}

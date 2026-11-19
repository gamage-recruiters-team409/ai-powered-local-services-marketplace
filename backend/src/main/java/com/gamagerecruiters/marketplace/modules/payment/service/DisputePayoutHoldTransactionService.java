
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
                    if (ledgerRepository.existsByEventKey(eventKey)) {
                        throw new IllegalStateException(
                                "Hold ledger event already exists "
                                        + "but hold is not APPLIED");
                    }

                    validateNoConflictingHold(hold);

                    // Validate the disputed booking's own ledger
                    // history before reserving provider earnings.
                    validateBookingAvailability(hold);

                    EarningsLedgerEntry entry = createHoldEvent(
                            hold, eventKey);

                    // The existing ledger transaction uses REQUIRED
                    // propagation and joins this outer transaction.
                    // It validates the aggregate provider balance,
                    // assigns the sequence and persists the event.
                    ledgerTransactionService.appendAtomically(entry);

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
                    || existing.getStatus() == HoldStatus.APPLIED) {

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

        List<EarningsLedgerEntry> entries = ledgerRepository.findByBookingIdOrderByCreatedAtDesc(
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
                        "Booking ledger contains conflicting "
                                + "financial ownership");
            }
        }

        EarningsBalanceCalculator.EarningsBalance balance = balanceCalculator.calculate(entries);

        BigDecimal available = balance.available();

        if (available == null
                || available.compareTo(hold.getAmount()) < 0) {

            throw new IllegalStateException(
                    "Insufficient booking-specific available earnings");
        }
    }

    /**
     * Builds a deterministic, validated earnings hold event.
     */
    private EarningsLedgerEntry createHoldEvent(
            DisputePayoutHold hold,
            String eventKey) {

        EarningsLedgerEntry entry = new EarningsLedgerEntry();

        entry.setEventKey(eventKey);
        entry.setPaymentId(hold.getPaymentId());
        entry.setBookingId(hold.getBookingId());
        entry.setProviderId(hold.getProviderId());
        entry.setEventType(EarningsLedgerEventType.EARNING_HOLD);
        entry.setAmount(hold.getAmount());
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

        EarningsLedgerEntry recorded = ledgerRepository.findByEventKey(eventKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Applied hold has no committed ledger event"));

        boolean matches = Objects.equals(eventKey, recorded.getEventKey())
                && Objects.equals(
                        hold.getPaymentId(), recorded.getPaymentId())
                && Objects.equals(
                        hold.getBookingId(), recorded.getBookingId())
                && Objects.equals(
                        hold.getProviderId(), recorded.getProviderId())
                && Objects.equals(
                        hold.getCurrency(), recorded.getCurrency())
                && recorded.getEventType() == EarningsLedgerEventType.EARNING_HOLD
                && recorded.getAmount() != null
                && recorded.getAmount().compareTo(
                        hold.getAmount()) == 0;

        if (!matches) {
            throw new IllegalStateException(
                    "Applied hold does not match its ledger event");
        }
    }

    /**
     * Rejects missing dispute identifiers.
     */
    private static void requireDisputeId(String disputeId) {

        if (disputeId == null || disputeId.isBlank()) {
            throw new IllegalArgumentException(
                    "Dispute ID is required");
        }
    }
}

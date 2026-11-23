
package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.Objects;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.dispute.port.PayoutHoldPort;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;

/**
 * Connects the WP06 dispute payout-hold contract to WP07 financial services.
 *
 * <p>
 * A successful call means the booking-specific earnings hold has been
 * applied and committed, not merely recorded as pending.
 * </p>
 *
 * <p>
 * Repeated calls for the same dispute are handled by the underlying
 * hold services, which validate the existing financial record.
 * </p>
 */
@Component
@Primary
public class DisputePayoutHoldAdapter implements PayoutHoldPort {

    private final BookingFacade bookingFacade;
    private final DisputePayoutHoldService holdService;
    private final DisputePayoutHoldTransactionService transactionService;

    public DisputePayoutHoldAdapter(
            BookingFacade bookingFacade,
            DisputePayoutHoldService holdService,
            DisputePayoutHoldTransactionService transactionService) {

        this.bookingFacade = Objects.requireNonNull(
                bookingFacade, "Booking facade is required");

        this.holdService = Objects.requireNonNull(
                holdService, "Hold service is required");

        this.transactionService = Objects.requireNonNull(
                transactionService, "Hold transaction service is required");
    }

    /**
     * Applies a financial payout hold for a disputed booking.
     *
     * @param bookingId booking referenced by the dispute
     * @param disputeId unique dispute identifier
     * @throws IllegalStateException if booking ownership or financial
     *                               reservation validation fails
     */
    @Override
    public void holdPayout(String bookingId, String disputeId) {

        requireText(bookingId, "Booking ID");
        requireText(disputeId, "Dispute ID");

        // Resolve provider ownership using the official Booking facade.
        BookingParticipants booking = Objects.requireNonNull(
                bookingFacade.getBookingParticipants(bookingId),
                "Booking participants are required");

        if (!bookingId.equals(booking.bookingId())
                || booking.providerId() == null
                || booking.providerId().isBlank()) {
            throw new IllegalStateException(
                    "Booking provider information is invalid");
        }

        // Preparation verifies payment ownership and records the
        // booking-specific hold request. It does not reserve funds.
        DisputePayoutHold prepared = holdService.prepareHold(
                disputeId,
                bookingId,
                booking.providerId());

        if (prepared == null) {
            throw new IllegalStateException(
                    "Dispute payout hold preparation failed");
        }

        // The transaction service commits the earnings ledger event,
        // provider balance update and APPLIED hold status atomically.
        DisputePayoutHold applied = transactionService.applyHold(disputeId);

        if (applied == null
                || applied.getStatus() != DisputePayoutHold.HoldStatus.APPLIED
                || !disputeId.equals(applied.getDisputeId())
                || !bookingId.equals(applied.getBookingId())
                || !booking.providerId().equals(applied.getProviderId())) {

            throw new IllegalStateException(
                    "Dispute payout hold was not confirmed as applied");
        }
    }

    /**
     * Rejects missing identifiers before accessing another module.
     */
    private static void requireText(String value, String fieldName) {

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank");
        }
    }
}


package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.domain.DisputePayoutHold;

@ExtendWith(MockitoExtension.class)
class DisputePayoutHoldAdapterTest {

    private static final String BOOKING_ID = "booking-1";
    private static final String DISPUTE_ID = "dispute-1";
    private static final String PROVIDER_ID = "provider-1";

    @Mock
    private BookingFacade bookingFacade;

    @Mock
    private DisputePayoutHoldService holdService;

    @Mock
    private DisputePayoutHoldTransactionService transactionService;

    private DisputePayoutHoldAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new DisputePayoutHoldAdapter(
                bookingFacade, holdService, transactionService);
    }

    /**
     * Verifies that a valid dispute reaches both financial services
     * and returns only after the hold is confirmed as applied.
     */
    @Test
    void holdPayout_appliesValidHold() {
        BookingParticipants participants = bookingParticipants();

        DisputePayoutHold hold = createHold();
        hold.markApplied();

        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(participants);
        when(holdService.prepareHold(
                DISPUTE_ID, BOOKING_ID, PROVIDER_ID))
                .thenReturn(createHold());
        when(transactionService.applyHold(DISPUTE_ID))
                .thenReturn(hold);

        assertDoesNotThrow(
                () -> adapter.holdPayout(BOOKING_ID, DISPUTE_ID));

        var inOrder = inOrder(
                bookingFacade, holdService, transactionService);

        inOrder.verify(bookingFacade)
                .getBookingParticipants(BOOKING_ID);
        inOrder.verify(holdService)
                .prepareHold(DISPUTE_ID, BOOKING_ID, PROVIDER_ID);
        inOrder.verify(transactionService)
                .applyHold(DISPUTE_ID);
    }

    /**
     * Invalid input must not reach the booking or financial services.
     */
    @Test
    void holdPayout_rejectsBlankIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.holdPayout(" ", DISPUTE_ID));

        assertThrows(IllegalArgumentException.class,
                () -> adapter.holdPayout(BOOKING_ID, null));

        verifyNoInteractions(
                bookingFacade, holdService, transactionService);
    }

    /**
     * A booking with inconsistent ownership cannot trigger a hold.
     */
    @Test
    void holdPayout_rejectsBookingMismatch() {
        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(new BookingParticipants(
                        "different-booking", null, null, null,
                        PROVIDER_ID, null, null, null));

        assertThrows(IllegalStateException.class,
                () -> adapter.holdPayout(BOOKING_ID, DISPUTE_ID));

        verifyNoInteractions(holdService, transactionService);
    }

    /**
     * A failed preparation must not execute a financial transaction.
     */
    @Test
    void holdPayout_stopsWhenPreparationFails() {
        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(bookingParticipants());

        when(holdService.prepareHold(
                DISPUTE_ID, BOOKING_ID, PROVIDER_ID))
                .thenThrow(new IllegalStateException("Invalid payment"));

        assertThrows(IllegalStateException.class,
                () -> adapter.holdPayout(BOOKING_ID, DISPUTE_ID));

        verifyNoInteractions(transactionService);
    }

    /**
     * A pending result cannot be reported as a successful hold.
     */
    @Test
    void holdPayout_rejectsUnappliedResult() {
        when(bookingFacade.getBookingParticipants(BOOKING_ID))
                .thenReturn(bookingParticipants());

        when(holdService.prepareHold(
                DISPUTE_ID, BOOKING_ID, PROVIDER_ID))
                .thenReturn(createHold());

        when(transactionService.applyHold(DISPUTE_ID))
                .thenReturn(createHold());

        assertThrows(IllegalStateException.class,
                () -> adapter.holdPayout(BOOKING_ID, DISPUTE_ID));
    }

    private BookingParticipants bookingParticipants() {
        return new BookingParticipants(
                BOOKING_ID, null, null, null,
                PROVIDER_ID, null, null, null);
    }

    private DisputePayoutHold createHold() {
        return new DisputePayoutHold(
                DISPUTE_ID,
                BOOKING_ID,
                "payment-1",
                PROVIDER_ID,
                new java.math.BigDecimal("100.00"),
                "LKR");
    }
}


package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.modules.booking.domain.BookingStatus;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingParticipants;
import org.springframework.security.access.AccessDeniedException;

/**
 * Validates customer access to bookings used by the payment module.
 *
 * <p>
 * All booking information is obtained through the WP05 BookingFacade.
 * This service does not access the booking repository directly.
 * </p>
 *
 * <p>
 * Authorization and payment initiation eligibility are separate checks.
 * A booking must also pass payment deadline and other business validations
 * before an actual payment can be initiated.
 * </p>
 */
@Service
public class PaymentBookingAuthorizationService {

    private final BookingFacade bookingFacade;

    /**
     * Creates the authorization service with the Booking module's facade.
     *
     * @param bookingFacade trusted interface for retrieving booking participants
     */
    public PaymentBookingAuthorizationService(BookingFacade bookingFacade) {
        this.bookingFacade = Objects.requireNonNull(
                bookingFacade,
                "BookingFacade must not be null");
    }

    /**
     * Verifies that the authenticated customer owns the requested booking.
     *
     * <p>
     * The caller must provide the authenticated user ID from the
     * application's security context, not from client-supplied input.
     * </p>
     *
     * @param bookingId  booking identifier to verify
     * @param customerId authenticated customer's user identifier
     * @return verified booking participants
     * @throws IllegalArgumentException if an identifier is missing
     * @throws AccessDeniedException    if the customer does not own the booking
     */
    public BookingParticipants requireCustomerOwnership(
            String bookingId,
            String customerId) {
        String validatedBookingId = requireNonBlank(
                bookingId,
                "Booking ID");

        String validatedCustomerId = requireNonBlank(
                customerId,
                "Customer ID");

        BookingParticipants participants = bookingFacade.getBookingParticipants(validatedBookingId);

        if (participants == null) {
            throw new IllegalStateException(
                    "BookingFacade returned no booking participants");
        }

        if (!validatedCustomerId.equals(participants.customerId())) {
            throw new AccessDeniedException(
                    "Customer is not authorized to access this booking");
        }

        return participants;
    }

    /**
     * Verifies ownership and checks the booking's payment initiation status.
     *
     * <p>
     * This method only checks the booking status. The caller must also
     * validate the 24-hour payment initiation deadline using trusted
     * provider acceptance information before creating a payment.
     * </p>
     *
     * @param bookingId  booking identifier
     * @param customerId authenticated customer's user identifier
     * @return verified booking participants eligible by status
     * @throws IllegalStateException if the booking is not awaiting payment
     */
    public BookingParticipants requirePaymentInitiationStatus(
            String bookingId,
            String customerId) {
        BookingParticipants participants = requireCustomerOwnership(bookingId, customerId);

        if (participants.status() != BookingStatus.AWAITING_PAYMENT) {
            throw new IllegalStateException(
                    "Payment initiation is allowed only when the booking "
                            + "status is AWAITING_PAYMENT");
        }

        return participants;
    }

    /**
     * Validates and normalizes a required identifier.
     *
     * @param value     identifier to validate
     * @param fieldName identifier description for error messages
     * @return trimmed identifier
     */
    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be null or blank");
        }

        return value.trim();
    }
}

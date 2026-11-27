package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.modules.booking.dto.BookingPaymentSnapshot;
import com.gamagerecruiters.marketplace.modules.booking.service.BookingFacade;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptDetailsResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptDetailsResponse.LineItem;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptResponse;

/**
 * Enriches verified customer receipts through the supported booking facade.
 *
 * <p>Receipt amounts originate from the original verified payment.
 * Unavailable pricing components and optional display fields are
 * never fabricated.</p>
 */
@Service
public class PaymentReceiptDetailsService {

    private final PaymentRetrievalService paymentRetrievalService;
    private final BookingFacade bookingFacade;

    public PaymentReceiptDetailsService(
            PaymentRetrievalService paymentRetrievalService,
            BookingFacade bookingFacade) {

        this.paymentRetrievalService = Objects.requireNonNull(
                paymentRetrievalService);

        this.bookingFacade = Objects.requireNonNull(bookingFacade);
    }

    /**
     * Retrieves receipt details after verifying customer ownership.
     *
     * @param paymentId associated payment identifier
     * @param customerId authenticated customer identifier
     * @return verified receipt details
     */
    public PaymentReceiptDetailsResponse getDetailsForCustomer(
            String paymentId,
            String customerId) {

        // Authorize receipt access before requesting booking information.
        PaymentReceiptResponse receipt = paymentRetrievalService
                .getReceiptForCustomer(paymentId, customerId);

        BookingPaymentSnapshot booking = bookingFacade
                .getPaymentSnapshot(receipt.bookingId());

        if (booking == null
                || !Objects.equals(receipt.bookingId(), booking.bookingId())
                || !Objects.equals(customerId, booking.customerId())) {

            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "PAYMENT_NOT_FOUND",
                    "Payment not found");
        }

        // Ensure the trusted booking snapshot agrees with the capture.
        if (booking.amount() == null
                || receipt.amount().compareTo(booking.amount()) != 0
                || !receipt.currency().equalsIgnoreCase(booking.currency())) {

            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "RECEIPT_BREAKDOWN_UNAVAILABLE",
                    "Verified receipt breakdown is not available");
        }

        // The current facade exposes the total, not individual price items.
        List<LineItem> items = List.of(
                new LineItem(
                        "Verified booking payment",
                        receipt.amount()));

        return new PaymentReceiptDetailsResponse(
                receipt,
                booking.bookingRef(),
                null,
                null,
                null,
                null,
                booking.providerId(),
                null,
                items);
    }
}

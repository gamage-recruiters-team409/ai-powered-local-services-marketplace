
package com.gamagerecruiters.marketplace.modules.payment.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentResponse;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentRetrievalService;

import org.springframework.http.HttpStatus;

/**
 * REST endpoints for customer-authorized payment retrieval.
 *
 * <p>
 * Only authenticated users with the CUSTOMER role may access
 * these endpoints. Booking ownership is verified by
 * PaymentRetrievalService before payment data is returned.
 * </p>
 *
 * <p>
 * Payment entities are mapped to PaymentResponse DTOs to prevent
 * exposure of internal gateway references and commission details.
 * </p>
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/payments")
@PreAuthorize("hasRole('CUSTOMER')")
public class PaymentController {

    private final PaymentRetrievalService paymentRetrievalService;

    /**
     * Creates the payment controller.
     *
     * @param paymentRetrievalService authorized payment retrieval service
     */
    public PaymentController(
            PaymentRetrievalService paymentRetrievalService) {
        this.paymentRetrievalService = paymentRetrievalService;
    }

    /**
     * Retrieves a single payment belonging to the authenticated customer.
     *
     * @param paymentId payment identifier
     * @return customer-facing payment details
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(
            @PathVariable String paymentId) {
        String customerId = requireAuthenticatedCustomerId();

        Payment payment = paymentRetrievalService.getPaymentForCustomer(
                paymentId,
                customerId);

        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    /**
     * Retrieves payment attempts for a booking owned by the customer.
     *
     * <p>
     * Booking ownership is verified before any payment records
     * are returned. An authorized booking without payment attempts
     * produces an empty list.
     * </p>
     *
     * @param bookingId booking identifier
     * @return customer-facing payment attempts for the booking
     */
    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<List<PaymentResponse>> getPaymentsForBooking(
            @PathVariable String bookingId) {
        String customerId = requireAuthenticatedCustomerId();

        List<PaymentResponse> responses = paymentRetrievalService
                .getPaymentsForBooking(bookingId, customerId)
                .stream()
                .map(PaymentResponse::from)
                .toList();

        return ResponseEntity.ok(responses);
    }

    /**
     * Retrieves the authenticated customer ID from the security context.
     *
     * <p>
     * Customer identity must never be trusted from a request
     * parameter, request body, or client-supplied header.
     * </p>
     *
     * @return authenticated customer identifier
     * @throws ApiException if the security context has no valid user ID
     */
    private String requireAuthenticatedCustomerId() {
        String customerId = CurrentUser.getUserId();

        if (customerId == null || customerId.isBlank()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Authentication is required to access payments");
        }

        return customerId;
    }
}


package com.gamagerecruiters.marketplace.modules.payment.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.payment.domain.Payment;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentReceiptResponse;
import com.gamagerecruiters.marketplace.modules.payment.dto.CustomerRefundPageResponse;
import com.gamagerecruiters.marketplace.modules.payment.service.CustomerRefundRetrievalService;
import com.gamagerecruiters.marketplace.modules.payment.dto.PaymentResponse;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentRetrievalService;
import com.gamagerecruiters.marketplace.modules.payment.service.PaymentReceiptPdfService;

/**
 * REST endpoints for customer-authorized payment retrieval.
 *
 * <p>
 * Only authenticated users with the CUSTOMER role may access
 * these endpoints. Individual payment and booking-based retrieval
 * operations verify booking ownership before returning records.
 * </p>
 *
 * <p>
 * The customer payment history endpoint uses the verified customer ID
 * snapshot stored on payment records. This supports the WP01 Customer
 * Payments List integration with database-level pagination.
 * </p>
 *
 * <p>
 * Payment entities are mapped to customer-facing DTOs to prevent
 * exposure of internal gateway references and commission details.
 * </p>
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/payments")
@PreAuthorize("hasRole('CUSTOMER')")
public class PaymentController {

    private final PaymentRetrievalService paymentRetrievalService;
    private final CustomerRefundRetrievalService customerRefundRetrievalService;
    private final PaymentReceiptPdfService paymentReceiptPdfService;

    /**
     * Creates the payment controller.
     *
     * @param paymentRetrievalService authorized payment retrieval service
     * @param customerRefundRetrievalService authorized refund history service
     * @param paymentReceiptPdfService authorized PDF receipt generation service
     */
    public PaymentController(
            PaymentRetrievalService paymentRetrievalService,
            CustomerRefundRetrievalService customerRefundRetrievalService,
            PaymentReceiptPdfService paymentReceiptPdfService) {
        this.paymentRetrievalService = paymentRetrievalService;
        this.customerRefundRetrievalService = customerRefundRetrievalService;
        this.paymentReceiptPdfService = paymentReceiptPdfService;
    }

    /**
     * Retrieves paginated payment history for the authenticated customer.
     *
     * <p>
     * Customer identity is obtained exclusively from the security
     * context. The endpoint does not accept a customer ID supplied
     * through request parameters or request bodies.
     * </p>
     *
     * <p>
     * Payments are ordered by creation time descending, followed
     * by payment ID descending. Pagination uses zero-based page
     * numbers and a maximum page size of 100.
     * </p>
     *
     * <p>
     * This endpoint supports the WP01 Customer Payments List.
     * </p>
     *
     * @param page zero-based page number; defaults to 0
     * @param size page size; defaults to 10
     * @return customer-facing paginated payment history
     */
    @GetMapping("/me")
    public ResponseEntity<PaymentPageResponse> getMyPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String customerId = requireAuthenticatedCustomerId();

        PaymentPageResponse response = PaymentPageResponse.from(
                paymentRetrievalService.getPaymentsForCustomer(
                        customerId,
                        page,
                        size));

        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves a single payment belonging to the authenticated customer.
     *
     * <p>
     * Booking ownership is verified by the retrieval service.
     * Missing or inaccessible payments return a generic 404.
     * </p>
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
     * Retrieves an issued receipt belonging to the authenticated customer.
     *
     * <p>Payment ownership and verified receipt eligibility are
     * enforced by the payment retrieval service.</p>
     *
     * @param paymentId associated payment identifier
     * @return verified customer-facing receipt
     */
    @GetMapping("/{paymentId}/receipt")
    public ResponseEntity<PaymentReceiptResponse> getPaymentReceipt(
            @PathVariable String paymentId) {

        String customerId = requireAuthenticatedCustomerId();

        PaymentReceiptResponse receipt = paymentRetrievalService
                .getReceiptForCustomer(paymentId, customerId);

        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(receipt);
    }

    /**
     * Retrieves payment attempts for a booking owned by the customer.
     *
     * <p>
     * Booking ownership is verified before payment records
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

        return customerId.trim();
    }

    /**
     * Retrieves paginated refund history for a customer-owned payment.
     *
     * <p>Returns customer-visible refund information without exposing
     * gateway references or internal financial processing details.</p>
     */
    @GetMapping("/{paymentId}/refunds")
    public ResponseEntity<CustomerRefundPageResponse> getPaymentRefunds(
            @PathVariable String paymentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String customerId = requireAuthenticatedCustomerId();

        CustomerRefundPageResponse refunds =
                customerRefundRetrievalService.getRefundsForCustomer(
                        paymentId, customerId, page, size);

        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(refunds);
    }

    /**
     * Downloads a verified receipt belonging to the authenticated customer.
     *
     * @param paymentId associated payment identifier
     * @return generated PDF attachment
     */
    @GetMapping(
            value = "/{paymentId}/receipt/download",
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadPaymentReceipt(
            @PathVariable String paymentId) {

        byte[] pdf;

        try {
            String customerId = requireAuthenticatedCustomerId();

            pdf = paymentReceiptPdfService.generateForCustomer(
                    paymentId, customerId);
        } catch (ApiException ex) {
            // Preserve structured JSON errors for PDF-only download requests.
            // Delegate error response construction to the shared exception handler.
            var attributes = org.springframework.web.context.request
                    .RequestContextHolder.getRequestAttributes();

            if (attributes instanceof org.springframework.web.context.request
                    .ServletRequestAttributes servletAttributes
                    && servletAttributes.getResponse() != null) {

                servletAttributes.getResponse()
                        .setContentType(MediaType.APPLICATION_JSON_VALUE);
            }

            throw ex;
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("payment-receipt.pdf")
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(pdf);
    }

}

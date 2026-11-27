package com.gamagerecruiters.marketplace.modules.payment.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.util.ApiPaths;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.payment.service.CustomerCheckoutService;
import com.gamagerecruiters.marketplace.modules.payment.service.CustomerCheckoutService.CheckoutResult;

/**
 * Secure REST endpoint for customer Checkout initiation and recovery.
 *
 * <p>
 * Customer identity is obtained exclusively from the authenticated
 * security context. Financial values and redirect URLs are resolved
 * through trusted server-side services.
 * </p>
 *
 * <p>
 * Customer Checkout is disabled by default until the complete
 * booking confirmation and late-payment lifecycle is verified.
 * </p>
 */
@RestController
@RequestMapping(ApiPaths.API_V1 + "/payments")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerCheckoutController {

    private final CustomerCheckoutService checkoutService;

    private final boolean customerCheckoutEnabled;

    /**
     * Creates the customer Checkout controller.
     *
     * @param checkoutService         secure Checkout orchestration service
     * @param customerCheckoutEnabled explicit feature activation flag
     */
    public CustomerCheckoutController(
            CustomerCheckoutService checkoutService,
            @Value("${app.payment.customer-checkout.enabled:false}") boolean customerCheckoutEnabled) {

        this.checkoutService = checkoutService;
        this.customerCheckoutEnabled = customerCheckoutEnabled;
    }

    /**
     * Creates or resumes Checkout for an authorized customer booking.
     *
     * <p>
     * No client-supplied customer identity, payment amount,
     * currency, commission rate or redirect URL is accepted
     * as an input to the Checkout service.
     * </p>
     *
     * @param bookingId booking identifier
     * @return customer-facing Checkout result
     */
    @PostMapping("/booking/{bookingId}/checkout")
    public ResponseEntity<CheckoutResult> checkout(
            @PathVariable String bookingId) {

        String customerId = requireAuthenticatedCustomerId();

        // Prevent financial operations before explicit activation.
        if (!customerCheckoutEnabled) {

            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CUSTOMER_CHECKOUT_DISABLED",
                    "Customer Checkout is currently unavailable");
        }

        if (bookingId == null || bookingId.isBlank()) {

            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BOOKING_ID",
                    "Booking ID must not be blank");
        }

        CheckoutResult result = checkoutService.checkout(
                bookingId.trim(),
                customerId);

        // Checkout URLs must not be stored by HTTP caches.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    /**
     * Resolves customer identity from the verified security context.
     *
     * @return authenticated customer identifier
     */
    private String requireAuthenticatedCustomerId() {

        String customerId = CurrentUser.getUserId();

        if (customerId == null || customerId.isBlank()) {

            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Authentication is required to access Checkout");
        }

        return customerId.trim();
    }
}
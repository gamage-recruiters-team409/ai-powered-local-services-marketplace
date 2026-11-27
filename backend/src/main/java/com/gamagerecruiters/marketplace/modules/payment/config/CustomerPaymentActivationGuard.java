package com.gamagerecruiters.marketplace.modules.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prevents premature activation of the customer payment workflow.
 *
 * <p>
 * The customer Checkout and booking confirmation implementations
 * remain unavailable for live financial operations until late-payment
 * reconciliation and recovery have been implemented and verified.
 * </p>
 *
 * <p>
 * This temporary safety guard does not modify or disable the
 * existing Stripe gateway and webhook capture functionality.
 * </p>
 */
@Component
public final class CustomerPaymentActivationGuard {

    /**
     * Validates the activation flags during application startup.
     *
     * @param customerCheckoutEnabled    customer Checkout activation flag
     * @param bookingConfirmationEnabled booking confirmation activation flag
     * @throws IllegalStateException if either incomplete workflow is enabled
     */
    public CustomerPaymentActivationGuard(
            @Value("${app.payment.customer-checkout.enabled:false}") boolean customerCheckoutEnabled,

            @Value("${app.payment.booking-confirmation.enabled:false}") boolean bookingConfirmationEnabled) {

        // Fail closed until the complete financial lifecycle is verified.
        if (customerCheckoutEnabled || bookingConfirmationEnabled) {
            throw new IllegalStateException(
                    "Customer payment workflow activation is blocked "
                            + "until late-payment reconciliation is implemented "
                            + "and verified");
        }
    }
}
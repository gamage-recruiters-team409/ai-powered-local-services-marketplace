package com.gamagerecruiters.marketplace.modules.payment.service;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Explicit, operator-controlled recovery of an interrupted checkout.
 *
 * Disabled by default. Requires an explicitly configured payment ID.
 * This runner never creates a new gateway session or captures a payment.
 *
 * Enable only in a controlled operational environment.
 * Remove the recovery configuration after the recovery attempt.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.payment.stripe.checkout-recovery",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class StripeCheckoutRecoveryRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(StripeCheckoutRecoveryRunner.class);

    private final StripeCheckoutRecoveryService recoveryService;
    private final String paymentId;

    public StripeCheckoutRecoveryRunner(
            StripeCheckoutRecoveryService recoveryService,
            @Value("${app.payment.stripe.checkout-recovery.payment-id:}")
            String paymentId) {

        this.recoveryService = Objects.requireNonNull(recoveryService);

        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException(
                    "An explicit payment ID is required for checkout recovery");
        }

        this.paymentId = paymentId.trim();
    }

    /**
     * Executes one recovery attempt for the configured payment.
     * Uncertain results require further operational investigation.
     */
    @Override
    public void run(ApplicationArguments args) {

        boolean attached =
                recoveryService.recoverCheckout(paymentId);

        if (attached) {
            log.info(
                    "Checkout recovery completed: session reference is present");
        } else {
            log.warn(
                    "Checkout recovery did not attach a session. "
                    + "Inspect the persisted reservation state before retrying.");
        }
    }
}
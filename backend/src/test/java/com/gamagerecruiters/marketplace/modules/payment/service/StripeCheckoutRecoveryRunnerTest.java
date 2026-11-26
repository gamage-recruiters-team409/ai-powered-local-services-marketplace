package com.gamagerecruiters.marketplace.modules.payment.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies explicit activation and safe delegation of checkout recovery.
 */
class StripeCheckoutRecoveryRunnerTest {

    private final StripeCheckoutRecoveryService recoveryService =
            mock(StripeCheckoutRecoveryService.class);

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            StripeCheckoutRecoveryRunner.class)
                    .withBean(
                            StripeCheckoutRecoveryService.class,
                            () -> recoveryService);

    @Test
    void recoveryIsDisabledByDefault() {

        contextRunner.run(context -> {
            assertFalse(context.containsBean(
                    "stripeCheckoutRecoveryRunner"));

            verifyNoInteractions(recoveryService);
        });
    }

    @Test
    void recoveryRemainsDisabledWhenExplicitlyDisabled() {

        contextRunner.withPropertyValues(
                "app.payment.stripe.checkout-recovery.enabled=false",
                "app.payment.stripe.checkout-recovery.payment-id=payment-1")
                .run(context -> {
                    assertFalse(context.containsBean(
                            "stripeCheckoutRecoveryRunner"));

                    verifyNoInteractions(recoveryService);
                });
    }

    @Test
    void rejectsEnabledRecoveryWithoutPaymentId() {

        contextRunner.withPropertyValues(
                "app.payment.stripe.checkout-recovery.enabled=true")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());

                    verifyNoInteractions(recoveryService);
                });
    }

    @Test
    void enabledRunnerDelegatesOnlyConfiguredPayment() {

        when(recoveryService.recoverCheckout("payment-1"))
                .thenReturn(true);

        contextRunner.withPropertyValues(
                "app.payment.stripe.checkout-recovery.enabled=true",
                "app.payment.stripe.checkout-recovery.payment-id=payment-1")
                .run(context -> {

                    assertNull(context.getStartupFailure());

                    StripeCheckoutRecoveryRunner runner =
                            context.getBean(
                                    StripeCheckoutRecoveryRunner.class);

                    runner.run(null);

                    verify(recoveryService)
                            .recoverCheckout("payment-1");

                    verifyNoMoreInteractions(recoveryService);
                });
    }

    @Test
    void unresolvedRecoveryDoesNotTriggerAutomaticRetry() {

        StripeCheckoutRecoveryRunner runner =
                new StripeCheckoutRecoveryRunner(
                        recoveryService, "payment-1");

        when(recoveryService.recoverCheckout("payment-1"))
                .thenReturn(false);

        assertDoesNotThrow(() -> runner.run(null));

        verify(recoveryService)
                .recoverCheckout("payment-1");

        verifyNoMoreInteractions(recoveryService);
    }

    @Test
    void propagatesRecoveryFailureWithoutAutomaticRetry() {

        StripeCheckoutRecoveryRunner runner =
                new StripeCheckoutRecoveryRunner(
                        recoveryService, "payment-1");

        when(recoveryService.recoverCheckout("payment-1"))
                .thenThrow(new IllegalStateException(
                        "Gateway unavailable"));

        assertThrows(IllegalStateException.class,
                () -> runner.run(null));

        verify(recoveryService)
                .recoverCheckout("payment-1");

        verifyNoMoreInteractions(recoveryService);
    }
}
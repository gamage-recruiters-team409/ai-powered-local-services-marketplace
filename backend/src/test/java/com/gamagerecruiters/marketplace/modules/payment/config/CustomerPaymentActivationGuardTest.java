package com.gamagerecruiters.marketplace.modules.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies fail-closed activation of the customer payment workflow.
 *
 * <p>
 * Tests actual Spring property binding and application context
 * startup behavior without invoking external financial operations.
 * </p>
 */
class CustomerPaymentActivationGuardTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    CustomerPaymentActivationGuard.class);

    /**
     * Missing activation properties must default to disabled.
     */
    @Test
    void defaultConfigurationAllowsStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(
                    CustomerPaymentActivationGuard.class);
        });
    }

    /**
     * Explicitly disabled workflows must allow startup.
     */
    @Test
    void explicitlyDisabledFlagsAllowStartup() {
        contextRunner
                .withPropertyValues(
                        "app.payment.customer-checkout.enabled=false",
                        "app.payment.booking-confirmation.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            CustomerPaymentActivationGuard.class);
                });
    }

    /**
     * Customer Checkout must not be activated prematurely.
     */
    @Test
    void enabledCustomerCheckoutRejectsStartup() {
        contextRunner
                .withPropertyValues(
                        "app.payment.customer-checkout.enabled=true",
                        "app.payment.booking-confirmation.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    IllegalStateException.class)
                            .hasStackTraceContaining(
                                    "Customer payment workflow activation is blocked");
                });
    }

    /**
     * Booking confirmation must not be activated prematurely.
     */
    @Test
    void enabledBookingConfirmationRejectsStartup() {
        contextRunner
                .withPropertyValues(
                        "app.payment.customer-checkout.enabled=false",
                        "app.payment.booking-confirmation.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    IllegalStateException.class)
                            .hasStackTraceContaining(
                                    "Customer payment workflow activation is blocked");
                });
    }

    /**
     * Enabling both incomplete workflows must also reject startup.
     */
    @Test
    void bothEnabledFlagsRejectStartup() {
        contextRunner
                .withPropertyValues(
                        "app.payment.customer-checkout.enabled=true",
                        "app.payment.booking-confirmation.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    IllegalStateException.class)
                            .hasStackTraceContaining(
                                    "Customer payment workflow activation is blocked");
                });
    }
}
package com.gamagerecruiters.marketplace.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

class DevLoggingEmailSenderConfigurationTest {

    private static final String[] APPROVED_PROFILES = {"local", "dev", "test", "integration-test"};

    @Test
    void approvedProfilesSelectDevSenderWhenProviderPropertyIsMissing() {
        for (String profile : APPROVED_PROFILES) {
            runWithProfile(profile, () -> new ApplicationContextRunner()
                    .withUserConfiguration(DevLoggingEmailSender.class))
                    .run(this::assertSingleDevSender);
        }
    }

    @Test
    void approvedProfilesSelectDevSenderWhenProviderIsDev() {
        for (String profile : APPROVED_PROFILES) {
            runWithProfile(profile, () -> new ApplicationContextRunner()
                    .withUserConfiguration(DevLoggingEmailSender.class)
                    .withPropertyValues("app.email.provider=dev"))
                    .run(this::assertSingleDevSender);
        }
    }

    @Test
    void productionLikeProfilesDoNotSelectDevSenderWhenProviderPropertyIsMissing() {
        assertNoSender(null, null);
        for (String profile : new String[]{"prod", "production", "staging"}) {
            assertNoSender(profile, null);
        }
    }

    @Test
    void productionLikeProfilesDoNotSelectDevSenderWhenProviderIsDev() {
        assertNoSender(null, "app.email.provider=dev");
        for (String profile : new String[]{"prod", "production", "staging"}) {
            assertNoSender(profile, "app.email.provider=dev");
        }
    }

    @Test
    void devProfileWithBrevoProviderDoesNotSelectDevSender() {
        runWithProfile("dev", () -> new ApplicationContextRunner()
                .withUserConfiguration(DevLoggingEmailSender.class)
                .withPropertyValues("app.email.provider=brevo"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EmailSender.class);
                });
    }

    @Test
    void productionLikeProfileFailsWithoutEmailSenderDependency() {
        runWithProfile("prod", () -> new ApplicationContextRunner()
                .withUserConfiguration(DevLoggingEmailSender.class, EmailSenderRequiredComponent.class)
                .withPropertyValues("app.email.provider=dev"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("EmailSender");
                });
    }

    @Test
    void localProfileStartsWithEmailSenderDependency() {
        runWithProfile("local", () -> new ApplicationContextRunner()
                .withUserConfiguration(DevLoggingEmailSender.class, EmailSenderRequiredComponent.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmailSender.class);
                });
    }

    private void assertNoSender(String profile, String property) {
        ApplicationContextRunner baseRunner = new ApplicationContextRunner()
                .withUserConfiguration(DevLoggingEmailSender.class);
        if (property != null) {
            baseRunner = baseRunner.withPropertyValues(property);
        }
        ApplicationContextRunner runner = baseRunner;
        runWithProfile(profile, () -> runner)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EmailSender.class);
                });
    }

    private void assertSingleDevSender(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        assertThat(context).hasSingleBean(EmailSender.class);
        assertThat(context).hasSingleBean(DevLoggingEmailSender.class);
    }

    private ApplicationContextRunner runWithProfile(
            String profile,
            Supplier<ApplicationContextRunner> runnerSupplier
    ) {
        return runnerSupplier.get().withInitializer(activeProfile(profile));
    }

    private ApplicationContextInitializer<ConfigurableApplicationContext> activeProfile(String profile) {
        return context -> {
            if (profile != null) {
                context.getEnvironment().setActiveProfiles(profile);
            }
        };
    }

    static class EmailSenderRequiredComponent {

        EmailSenderRequiredComponent(EmailSender emailSender) {
        }
    }
}

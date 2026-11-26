package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

@ExtendWith(MockitoExtension.class)
class ProfileImageCleanupJobTest {

    @Mock
    private ProfileImageCleanupService cleanupService;

    @Test
    @DisplayName("Constructor rejects batchSize < 1")
    void constructor_rejectsBatchSizeLessThanOne() {
        assertThrows(IllegalArgumentException.class, () ->
                new ProfileImageCleanupJob(cleanupService, 0, "PT5M"));
        assertThrows(IllegalArgumentException.class, () ->
                new ProfileImageCleanupJob(cleanupService, -5, "PT5M"));
    }

    @Test
    @DisplayName("Scheduled run() calls retryPending with configured batchSize")
    void run_callsRetryPending() {
        ProfileImageCleanupJob job = new ProfileImageCleanupJob(cleanupService, 25, "PT5M");
        when(cleanupService.retryPending(25)).thenReturn(3);

        job.run();

        verify(cleanupService).retryPending(25);
    }

    @Test
    @DisplayName("Scheduled run() swallows RuntimeException without throwing")
    void run_swallowsRuntimeException() {
        ProfileImageCleanupJob job = new ProfileImageCleanupJob(cleanupService, 25, "PT5M");
        when(cleanupService.retryPending(25)).thenThrow(new RuntimeException("Database unreachable"));

        assertDoesNotThrow(job::run);
    }

    @Test
    @DisplayName("Job wiring: bean exists and @Scheduled method registered when enabled=true")
    void contextRunner_enabled_beanAndScheduledTaskExist() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProfileImageCleanupJob.class, ProfileImageCleanupSchedulingConfig.class)
                .withBean(ProfileImageCleanupService.class, () -> mock(ProfileImageCleanupService.class))
                .withPropertyValues(
                        "app.customer.profile-image-cleanup.enabled=true",
                        "app.customer.profile-image-cleanup.batch-size=25",
                        "app.customer.profile-image-cleanup.interval=PT5M"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ProfileImageCleanupJob.class);
                    ScheduledAnnotationBeanPostProcessor bpp = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertFalse(bpp.getScheduledTasks().isEmpty(), "Scheduled tasks should not be empty");
                });
    }

    @Test
    @DisplayName("Job wiring: bean is absent when enabled=false")
    void contextRunner_disabled_beanAbsent() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProfileImageCleanupJob.class, ProfileImageCleanupSchedulingConfig.class)
                .withBean(ProfileImageCleanupService.class, () -> mock(ProfileImageCleanupService.class))
                .withPropertyValues(
                        "app.customer.profile-image-cleanup.enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ProfileImageCleanupJob.class);
                });
    }
}

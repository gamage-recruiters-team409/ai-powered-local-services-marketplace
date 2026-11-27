package com.gamagerecruiters.marketplace.modules.admin.initializer;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.gamagerecruiters.marketplace.modules.admin.service.PlatformSettingsService;

/**
 * Seeds default platform configuration keys on application startup if not present.
 */
@Component
@Order(100)
public class PlatformSettingsInitializer implements ApplicationRunner {

    private final PlatformSettingsService settingsService;

    public PlatformSettingsInitializer(PlatformSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        settingsService.seedDefaults();
    }
}

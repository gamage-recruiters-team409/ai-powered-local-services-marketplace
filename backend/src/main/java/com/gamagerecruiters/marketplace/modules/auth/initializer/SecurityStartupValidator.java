package com.gamagerecruiters.marketplace.modules.auth.initializer;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class SecurityStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupValidator.class);
    private static final List<String> PROTECTED_PROFILES = List.of("prod", "production", "staging");

    private final boolean requireEmailVerification;
    private final Environment environment;

    public SecurityStartupValidator(
            @Value("${app.auth.require-email-verification:false}") boolean requireEmailVerification,
            Environment environment
    ) {
        this.requireEmailVerification = requireEmailVerification;
        this.environment = environment;
    }

    @PostConstruct
    public void validateSecurityConfiguration() {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());

        if (!requireEmailVerification) {
            for (String profile : activeProfiles) {
                if (PROTECTED_PROFILES.contains(profile.toLowerCase())) {
                    throw new IllegalStateException(
                            "Security violation: app.auth.require-email-verification cannot be false in " +
                            "environment profile: '" + profile + "'. Email verification must be enforced."
                    );
                }
            }

            log.warn("================================================================================");
            log.warn("WARNING: app.auth.require-email-verification is DISABLED (local dev bypass).");
            log.warn("New registrations will receive status ACTIVE, but emailVerified remains FALSE.");
            log.warn("TODO: Enable email verification once the verification module is completed.");
            log.warn("================================================================================");
        }
    }
}

package com.gamagerecruiters.marketplace.modules.auth.initializer;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class SecurityStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupValidator.class);

    private static final Set<String> ALLOWED_BYPASS_PROFILES = Set.of("local", "dev", "test", "integration-test");
    private static final Set<String> PROTECTED_PROFILES = Set.of("prod", "production", "staging", "stage", "uat");

    private final boolean requireEmailVerification;
    private final Environment environment;

    public SecurityStartupValidator(
            @Value("${app.auth.require-email-verification:true}") boolean requireEmailVerification,
            Environment environment
    ) {
        this.requireEmailVerification = requireEmailVerification;
        this.environment = environment;
    }

    @PostConstruct
    public void validateSecurityConfiguration() {
        String[] profiles = environment.getActiveProfiles();
        List<String> activeProfiles = Arrays.stream(profiles)
                .map(String::trim)
                .map(String::toLowerCase)
                .toList();

        if (!requireEmailVerification) {
            if (activeProfiles.isEmpty()) {
                throw new IllegalStateException(
                        "Security violation: app.auth.require-email-verification cannot be false without an explicitly configured active profile. " +
                        "Email verification bypass is strictly restricted to development profiles: " + ALLOWED_BYPASS_PROFILES
                );
            }

            for (String profile : activeProfiles) {
                if (PROTECTED_PROFILES.contains(profile)) {
                    throw new IllegalStateException(
                            "Security violation: app.auth.require-email-verification cannot be false in protected profile: '" + profile + "'. " +
                            "Email verification must be enforced in production and pre-production environments."
                    );
                }
            }

            boolean hasAllowedProfile = activeProfiles.stream().anyMatch(ALLOWED_BYPASS_PROFILES::contains);
            if (!hasAllowedProfile) {
                throw new IllegalStateException(
                        "Security violation: app.auth.require-email-verification cannot be false in unknown/unauthorized profile(s): " +
                        activeProfiles + ". Email verification bypass is strictly restricted to profiles: " + ALLOWED_BYPASS_PROFILES
                );
            }

            log.warn("================================================================================");
            log.warn("WARNING: app.auth.require-email-verification is DISABLED (dev bypass enabled).");
            log.warn("Active profile(s): {}", activeProfiles);
            log.warn("New registrations will receive status ACTIVE, but emailVerified remains FALSE.");
            log.warn("TODO: Enable email verification once the verification module is completed.");
            log.warn("================================================================================");
        }
    }
}

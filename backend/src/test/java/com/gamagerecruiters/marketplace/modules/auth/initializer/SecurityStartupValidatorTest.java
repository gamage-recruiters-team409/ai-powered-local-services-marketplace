package com.gamagerecruiters.marketplace.modules.auth.initializer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class SecurityStartupValidatorTest {

    @Test
    @DisplayName("Issue 4: Startup fails if REQUIRE_EMAIL_VERIFICATION is false in prod profile")
    void shouldFailStartupWhenBypassOnInProd() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validateSecurityConfiguration);
        assertTrue(ex.getMessage().contains("Security violation"));
    }

    @Test
    @DisplayName("Issue 4: Startup fails if REQUIRE_EMAIL_VERIFICATION is false in staging profile")
    void shouldFailStartupWhenBypassOnInStaging() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"staging"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validateSecurityConfiguration);
        assertTrue(ex.getMessage().contains("Security violation"));
    }

    @Test
    @DisplayName("Issue 4: Startup succeeds when REQUIRE_EMAIL_VERIFICATION is false in local/dev profile")
    void shouldSucceedStartupWhenBypassOnInDev() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        assertDoesNotThrow(validator::validateSecurityConfiguration);
    }

    @Test
    @DisplayName("Issue 4: Startup succeeds when REQUIRE_EMAIL_VERIFICATION is true in prod profile")
    void shouldSucceedStartupWhenVerificationTrueInProd() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        SecurityStartupValidator validator = new SecurityStartupValidator(true, env);

        assertDoesNotThrow(validator::validateSecurityConfiguration);
    }
}

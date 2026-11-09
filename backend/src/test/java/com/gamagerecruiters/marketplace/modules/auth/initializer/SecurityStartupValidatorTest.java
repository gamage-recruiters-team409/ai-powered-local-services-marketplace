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
    @DisplayName("Issue B: Startup fails if bypass=false with no active profile (empty)")
    void shouldFailWhenBypassOnAndNoProfile() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validateSecurityConfiguration);
        assertTrue(ex.getMessage().contains("Security violation"));
        assertTrue(ex.getMessage().contains("without an explicitly configured active profile"));
    }

    @Test
    @DisplayName("Issue B: Startup fails if bypass=false with unknown profile")
    void shouldFailWhenBypassOnAndUnknownProfile() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"sandbox"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validateSecurityConfiguration);
        assertTrue(ex.getMessage().contains("Security violation"));
        assertTrue(ex.getMessage().contains("unknown/unauthorized"));
    }

    @Test
    @DisplayName("Issue B: Startup fails if bypass=false in prod profile")
    void shouldFailWhenBypassOnInProd() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validateSecurityConfiguration);
        assertTrue(ex.getMessage().contains("Security violation"));
        assertTrue(ex.getMessage().contains("protected profile"));
    }

    @Test
    @DisplayName("Issue B: Startup fails if bypass=false in production profile")
    void shouldFailWhenBypassOnInProduction() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"production"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validateSecurityConfiguration);
        assertTrue(ex.getMessage().contains("protected profile"));
    }

    @Test
    @DisplayName("Issue B: Startup fails if bypass=false in staging profile")
    void shouldFailWhenBypassOnInStaging() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"staging"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        assertThrows(IllegalStateException.class, validator::validateSecurityConfiguration);
    }

    @Test
    @DisplayName("Issue B: Startup fails if bypass=false in stage profile")
    void shouldFailWhenBypassOnInStage() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"stage"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        assertThrows(IllegalStateException.class, validator::validateSecurityConfiguration);
    }

    @Test
    @DisplayName("Issue B: Startup fails if bypass=false in uat profile")
    void shouldFailWhenBypassOnInUat() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"uat"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        assertThrows(IllegalStateException.class, validator::validateSecurityConfiguration);
    }

    @Test
    @DisplayName("Issue B: Startup fails if bypass=false with mixed local+prod profiles (protected profile wins)")
    void shouldFailWhenBypassOnWithMixedLocalAndProd() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"local", "prod"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validateSecurityConfiguration);
        assertTrue(ex.getMessage().contains("protected profile"));
    }

    @Test
    @DisplayName("Issue B: Startup succeeds if bypass=false in local profile")
    void shouldSucceedWhenBypassOnInLocal() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"local"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        assertDoesNotThrow(validator::validateSecurityConfiguration);
    }

    @Test
    @DisplayName("Issue B: Startup succeeds if bypass=false in dev profile")
    void shouldSucceedWhenBypassOnInDev() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        assertDoesNotThrow(validator::validateSecurityConfiguration);
    }

    @Test
    @DisplayName("Issue B: Startup succeeds if bypass=false in test profile")
    void shouldSucceedWhenBypassOnInTest() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"test"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        assertDoesNotThrow(validator::validateSecurityConfiguration);
    }

    @Test
    @DisplayName("Finding 2: Startup succeeds if bypass=false in integration-test profile")
    void shouldSucceedWhenBypassOnInIntegrationTest() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"integration-test"});

        SecurityStartupValidator validator = new SecurityStartupValidator(false, env);

        assertDoesNotThrow(validator::validateSecurityConfiguration);
    }

    @Test
    @DisplayName("Issue B: Startup succeeds when verification=true regardless of profile (prod)")
    void shouldSucceedWhenVerificationTrueInProd() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        SecurityStartupValidator validator = new SecurityStartupValidator(true, env);

        assertDoesNotThrow(validator::validateSecurityConfiguration);
    }

    @Test
    @DisplayName("Issue B: Startup succeeds when verification=true with no profile")
    void shouldSucceedWhenVerificationTrueWithNoProfile() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{});

        SecurityStartupValidator validator = new SecurityStartupValidator(true, env);

        assertDoesNotThrow(validator::validateSecurityConfiguration);
    }
}

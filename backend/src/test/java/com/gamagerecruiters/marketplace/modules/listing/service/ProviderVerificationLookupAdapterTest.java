package com.gamagerecruiters.marketplace.modules.listing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.dto.ProviderVerificationResponse;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationService;

@ExtendWith(MockitoExtension.class)
class ProviderVerificationLookupAdapterTest {

    @Mock
    private ProviderVerificationService providerVerificationService;

    private ProviderVerificationLookupAdapter adapter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        adapter = new ProviderVerificationLookupAdapter(providerVerificationService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String userId, Role role) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(userId, userId + "@test.local", role),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        ));
        SecurityContextHolder.setContext(ctx);
    }

    private ProviderVerificationResponse statusResponse(ProviderVerificationStatus status) {
        return new ProviderVerificationResponse(
                "ver-1",
                status,
                Instant.now(),
                Instant.now(),
                null,
                Instant.now()
        );
    }

    @Test
    @DisplayName("Returns true for APPROVED provider and restores prior SecurityContext when checked by another actor")
    void returnsTrueForApprovedAndRestoresModeratorContext() {
        authenticateAs("prov-1", Role.PROVIDER);
        when(providerVerificationService.getMyVerificationStatus())
                .thenReturn(statusResponse(ProviderVerificationStatus.APPROVED));

        assertThat(adapter.isProviderVerified("prov-1")).isTrue();
        assertThat(CurrentUser.getUserId()).isEqualTo("prov-1");

        authenticateAs("moderator-1", Role.MODERATOR);
        when(providerVerificationService.getMyVerificationStatus()).thenAnswer(inv -> {
            assertThat(CurrentUser.getUserId()).isEqualTo("prov-2");
            assertThat(CurrentUser.getRole()).isEqualTo(Role.PROVIDER);
            return statusResponse(ProviderVerificationStatus.APPROVED);
        });

        assertThat(adapter.isProviderVerified("prov-2")).isTrue();
        assertThat(CurrentUser.getUserId()).isEqualTo("moderator-1");
        assertThat(CurrentUser.getRole()).isEqualTo(Role.MODERATOR);
    }

    @ParameterizedTest
    @EnumSource(
            value = ProviderVerificationStatus.class,
            names = {"NOT_SUBMITTED", "SUBMITTED", "UNDER_REVIEW", "REJECTED", "RESUBMISSION_REQUESTED", "SUSPENDED", "EXPIRED"}
    )
    @DisplayName("Returns false for every non-APPROVED verification status")
    void returnsFalseForUnapprovedStatuses(ProviderVerificationStatus status) {
        authenticateAs("prov-1", Role.PROVIDER);
        when(providerVerificationService.getMyVerificationStatus()).thenReturn(statusResponse(status));

        assertThat(adapter.isProviderVerified("prov-1")).isFalse();
    }

    @Test
    @DisplayName("Returns false when provider profile/verification is missing, response is null, or userId is blank")
    void returnsFalseWhenMissingOrBlank() {
        assertThat(adapter.isProviderVerified(null)).isFalse();
        assertThat(adapter.isProviderVerified("   ")).isFalse();

        authenticateAs("moderator-1", Role.MODERATOR);
        when(providerVerificationService.getMyVerificationStatus())
                .thenThrow(new ResourceNotFoundException("PROVIDER_PROFILE_NOT_FOUND", "Not found"));

        assertThat(adapter.isProviderVerified("prov-missing")).isFalse();
        assertThat(CurrentUser.getUserId()).isEqualTo("moderator-1");

        doReturn(null).when(providerVerificationService).getMyVerificationStatus();
        assertThat(adapter.isProviderVerified("prov-null")).isFalse();
        assertThat(CurrentUser.getUserId()).isEqualTo("moderator-1");
    }
}

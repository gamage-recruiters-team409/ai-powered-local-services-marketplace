package com.gamagerecruiters.marketplace.modules.listing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ApiException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.domain.User;
import com.gamagerecruiters.marketplace.modules.auth.domain.UserStatus;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.auth.service.UserInternalService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerification;
import com.gamagerecruiters.marketplace.modules.verification.domain.ProviderVerificationStatus;
import com.gamagerecruiters.marketplace.modules.verification.repository.ProviderVerificationRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacadeImpl;

@ExtendWith(MockitoExtension.class)
class ProviderVerificationLookupAdapterTest {

    @Mock
    private ProviderVerificationFacade providerVerificationFacade;

    private ProviderVerificationLookupAdapter adapter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        adapter = new ProviderVerificationLookupAdapter(providerVerificationFacade);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private SecurityContext authenticateAs(String userId, Role role) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(userId, userId + "@test.local", role),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        ));
        SecurityContextHolder.setContext(ctx);
        return ctx;
    }

    private User activeProvider(String userId) {
        User user = new User();
        user.setId(userId);
        user.setRole(Role.PROVIDER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    @Test
    @DisplayName("Delegates by trimmed providerUserId, allows APPROVED provider, and never switches SecurityContext during moderator lookup")
    void delegatesDirectlyAndPreservesModeratorSecurityContext() {
        when(providerVerificationFacade.isApprovedProvider("prov-1")).thenReturn(true);
        assertThat(adapter.isProviderVerified("  prov-1  ")).isTrue();
        verify(providerVerificationFacade).isApprovedProvider("prov-1");

        SecurityContext moderatorContext = authenticateAs("moderator-1", Role.MODERATOR);
        Authentication moderatorAuth = moderatorContext.getAuthentication();

        when(providerVerificationFacade.isApprovedProvider("prov-owner-2")).thenAnswer(inv -> {
            assertThat(SecurityContextHolder.getContext()).isSameAs(moderatorContext);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(moderatorAuth);
            assertThat(CurrentUser.getUserId()).isEqualTo("moderator-1");
            assertThat(CurrentUser.getRole()).isEqualTo(Role.MODERATOR);
            return true;
        });

        assertThat(adapter.isProviderVerified("prov-owner-2")).isTrue();
        assertThat(SecurityContextHolder.getContext()).isSameAs(moderatorContext);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(moderatorAuth);
        assertThat(CurrentUser.getUserId()).isEqualTo("moderator-1");
        assertThat(CurrentUser.getRole()).isEqualTo(Role.MODERATOR);
    }

    @Test
    @DisplayName("Fails closed on null/blank userId, null facade, and ResourceNotFoundException without changing SecurityContext")
    void failsClosedWhenMissingUnavailableOrBlank() {
        assertThat(adapter.isProviderVerified(null)).isFalse();
        assertThat(adapter.isProviderVerified("   ")).isFalse();
        verifyNoInteractions(providerVerificationFacade);

        ProviderVerificationLookupAdapter nullFacadeAdapter = new ProviderVerificationLookupAdapter(null);
        assertThat(nullFacadeAdapter.isProviderVerified("prov-1")).isFalse();

        SecurityContext moderatorContext = authenticateAs("moderator-1", Role.MODERATOR);
        when(providerVerificationFacade.isApprovedProvider("prov-missing"))
                .thenThrow(new ResourceNotFoundException("PROVIDER_PROFILE_NOT_FOUND", "Not found"));

        assertThat(adapter.isProviderVerified("prov-missing")).isFalse();
        assertThat(SecurityContextHolder.getContext()).isSameAs(moderatorContext);
        assertThat(CurrentUser.getUserId()).isEqualTo("moderator-1");
    }

    @Test
    @DisplayName("Fails closed with 503 PROVIDER_VERIFICATION_UNAVAILABLE on temporary WP02 lookup failures without mutating SecurityContext")
    void failsClosedWith503WhenFacadeThrowsUnexpectedRuntimeException() {
        SecurityContext moderatorContext = authenticateAs("moderator-1", Role.MODERATOR);
        when(providerVerificationFacade.isApprovedProvider("prov-outage"))
                .thenThrow(new IllegalStateException("MongoDB connection timeout"));

        assertThatThrownBy(() -> adapter.isProviderVerified("prov-outage"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(apiEx.getErrorCode()).isEqualTo("PROVIDER_VERIFICATION_UNAVAILABLE");
                });
        assertThat(SecurityContextHolder.getContext()).isSameAs(moderatorContext);
        assertThat(CurrentUser.getUserId()).isEqualTo("moderator-1");
    }

    @Test
    @DisplayName("End-to-end WP02 ProviderVerificationFacadeImpl + WP03 eligibility allows APPROVED provider and fails closed when verification is missing")
    void realFacadeIntegrationApprovedAllowedAndMissingBlocked() {
        UserInternalService userInternalService = mock(UserInternalService.class);
        ProviderProfileRepository profileRepo = mock(ProviderProfileRepository.class);
        ProviderVerificationRepository verificationRepo = mock(ProviderVerificationRepository.class);

        ProviderVerificationFacade realFacade = new ProviderVerificationFacadeImpl(profileRepo, verificationRepo);
        ProviderVerificationPort realAdapter = new ProviderVerificationLookupAdapter(realFacade);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderVerificationPort> portProvider = mock(ObjectProvider.class);
        when(portProvider.getIfAvailable()).thenReturn(realAdapter);

        ProviderEligibilityChecker eligibilityChecker = new ProviderEligibilityCheckerImpl(userInternalService, portProvider);

        ProviderProfile approvedProfile = mock(ProviderProfile.class);
        when(approvedProfile.getId()).thenReturn("profile-approved");
        ProviderVerification approvedVerification = new ProviderVerification();
        approvedVerification.setStatus(ProviderVerificationStatus.APPROVED);

        when(userInternalService.getById("prov-approved")).thenReturn(activeProvider("prov-approved"));
        when(profileRepo.findByUserId("prov-approved")).thenReturn(Optional.of(approvedProfile));
        when(verificationRepo.findByProviderId("profile-approved")).thenReturn(Optional.of(approvedVerification));

        assertThatCode(() -> eligibilityChecker.assertEligibleToManageListing("prov-approved", true))
                .doesNotThrowAnyException();

        // Missing verification record must fail closed with 403 PROVIDER_NOT_VERIFIED
        ProviderProfile unverifiedProfile = mock(ProviderProfile.class);
        when(unverifiedProfile.getId()).thenReturn("profile-missing-ver");
        when(userInternalService.getById("prov-missing-ver")).thenReturn(activeProvider("prov-missing-ver"));
        when(profileRepo.findByUserId("prov-missing-ver")).thenReturn(Optional.of(unverifiedProfile));
        when(verificationRepo.findByProviderId("profile-missing-ver")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eligibilityChecker.assertEligibleToManageListing("prov-missing-ver", true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiEx.getErrorCode()).isEqualTo("PROVIDER_NOT_VERIFIED");
                });
    }

    @ParameterizedTest
    @EnumSource(
            value = ProviderVerificationStatus.class,
            names = {"NOT_SUBMITTED", "SUBMITTED", "UNDER_REVIEW", "REJECTED", "RESUBMISSION_REQUESTED", "SUSPENDED", "EXPIRED"}
    )
    @DisplayName("End-to-end WP02 ProviderVerificationFacadeImpl rejects publish for every non-APPROVED verification status")
    void realFacadeIntegrationRejectsEveryNonApprovedStatus(ProviderVerificationStatus nonApprovedStatus) {
        UserInternalService userInternalService = mock(UserInternalService.class);
        ProviderProfileRepository profileRepo = mock(ProviderProfileRepository.class);
        ProviderVerificationRepository verificationRepo = mock(ProviderVerificationRepository.class);

        ProviderVerificationFacade realFacade = new ProviderVerificationFacadeImpl(profileRepo, verificationRepo);
        ProviderVerificationPort realAdapter = new ProviderVerificationLookupAdapter(realFacade);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderVerificationPort> portProvider = mock(ObjectProvider.class);
        when(portProvider.getIfAvailable()).thenReturn(realAdapter);

        ProviderEligibilityChecker eligibilityChecker = new ProviderEligibilityCheckerImpl(userInternalService, portProvider);

        ProviderProfile profile = mock(ProviderProfile.class);
        when(profile.getId()).thenReturn("profile-1");
        ProviderVerification verification = new ProviderVerification();
        verification.setStatus(nonApprovedStatus);

        when(userInternalService.getById("prov-1")).thenReturn(activeProvider("prov-1"));
        when(profileRepo.findByUserId("prov-1")).thenReturn(Optional.of(profile));
        when(verificationRepo.findByProviderId("profile-1")).thenReturn(Optional.of(verification));

        assertThatThrownBy(() -> eligibilityChecker.assertEligibleToManageListing("prov-1", true))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiEx.getErrorCode()).isEqualTo("PROVIDER_NOT_VERIFIED");
                });
        verify(profileRepo).findByUserId("prov-1");
        verify(verificationRepo).findByProviderId("profile-1");
    }
}

package com.gamagerecruiters.marketplace.modules.provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;
import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.filestorage.ProfileImageStorageService;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationFacade;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationReverificationService;

@ExtendWith(MockitoExtension.class)
class ProviderServiceAreaLegacyCompatibilityTest {

    private static final String USER_ID = "provider-user-legacy";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private ProviderVerificationFacade providerVerificationFacade;

    @Mock
    private DistrictCatalog districtCatalog;

    @Mock
    private CategoryService categoryService;

    @Mock
    private ProviderVerificationReverificationService reverificationService;

    @Mock
    private ProfileImageStorageService profileImageStorageService;

    private ProviderActivityFacadeImpl activityFacade;

    private ProviderProfileServiceImpl profileService;

    @BeforeEach
    void setUp() {
        activityFacade = new ProviderActivityFacadeImpl(
                providerProfileRepository,
                providerVerificationFacade,
                districtCatalog
        );
        profileService = new ProviderProfileServiceImpl(
                providerProfileRepository,
                categoryService,
                districtCatalog,
                reverificationService,
                profileImageStorageService,
                org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class)
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyMissingFieldUsesValidatedPrimaryDistrictInBothContractsAndResponse() {
        ProviderProfile profile = profile("colombo");
        assertFalse(profile.hasStoredServiceAreaDistricts());

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));
        when(districtCatalog.isValidDistrict("colombo")).thenReturn(true);
        when(districtCatalog.getAllDistricts())
                .thenReturn(Set.of("Colombo", "Gampaha"));

        assertEquals(Set.of("Colombo"), activityFacade.getServiceAreaDistricts(USER_ID));
        assertEquals(Set.of("Colombo"), profileService.getServiceAreaDistricts(USER_ID));

        authenticateProvider();
        assertEquals(List.of("Colombo"),
                profileService.getMyProfile().getServiceAreaDistricts());
    }

    @Test
    void explicitlyStoredEmptySelectionDoesNotFallBackToPrimaryDistrict() {
        ProviderProfile profile = profile("Colombo");
        profile.setServiceAreaDistricts(List.of());
        assertTrue(profile.hasStoredServiceAreaDistricts());

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        assertEquals(Set.of(), activityFacade.getServiceAreaDistricts(USER_ID));
        assertEquals(Set.of(), profileService.getServiceAreaDistricts(USER_ID));

        authenticateProvider();
        assertEquals(List.of(),
                profileService.getMyProfile().getServiceAreaDistricts());

        verifyNoInteractions(districtCatalog);
    }

    @Test
    void configuredMultiDistrictProfileDoesNotUseLegacyFallback() {
        ProviderProfile profile = profile("Galle");
        profile.setServiceAreaDistricts(List.of("Colombo", "Gampaha", "Kalutara"));

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        Set<String> expected = Set.of("Colombo", "Gampaha", "Kalutara");
        assertEquals(expected, activityFacade.getServiceAreaDistricts(USER_ID));
        assertEquals(expected, profileService.getServiceAreaDistricts(USER_ID));

        authenticateProvider();
        assertEquals(List.of("Colombo", "Gampaha", "Kalutara"),
                profileService.getMyProfile().getServiceAreaDistricts());

        verifyNoInteractions(districtCatalog);
    }

    @Test
    void invalidLegacyDistrictDoesNotBecomeBookingServiceArea() {
        ProviderProfile profile = profile("Not A District");
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));
        when(districtCatalog.isValidDistrict("Not A District"))
                .thenReturn(false);

        assertEquals(Set.of(), activityFacade.getServiceAreaDistricts(USER_ID));
        assertEquals(Set.of(), profileService.getServiceAreaDistricts(USER_ID));

        authenticateProvider();
        assertEquals(List.of(),
                profileService.getMyProfile().getServiceAreaDistricts());
    }

    @Test
    void missingProfileAndBlankIdsReturnEmptySet() {
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.empty());

        assertEquals(Set.of(), activityFacade.getServiceAreaDistricts(USER_ID));
        assertEquals(Set.of(), profileService.getServiceAreaDistricts(USER_ID));
        assertEquals(Set.of(), activityFacade.getServiceAreaDistricts("  "));
        assertEquals(Set.of(), profileService.getServiceAreaDistricts(null));
    }

    @Test
    void resolvedLegacyDistrictSetIsImmutable() {
        ProviderProfile profile = profile("Colombo");
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));
        when(districtCatalog.isValidDistrict("Colombo")).thenReturn(true);
        when(districtCatalog.getAllDistricts()).thenReturn(Set.of("Colombo"));

        Set<String> result = activityFacade.getServiceAreaDistricts(USER_ID);
        assertThrows(UnsupportedOperationException.class,
                () -> result.add("Gampaha"));
    }

    private ProviderProfile profile(String primaryDistrict) {
        ProviderProfile profile = new ProviderProfile();
        profile.setId("profile-123");
        profile.setUserId(USER_ID);
        profile.setDistrict(primaryDistrict);
        return profile;
    }

    private void authenticateProvider() {
        UserPrincipal principal = new UserPrincipal(
                USER_ID, "legacy-provider@test.com", Role.PROVIDER
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()
                )
        );
    }
}

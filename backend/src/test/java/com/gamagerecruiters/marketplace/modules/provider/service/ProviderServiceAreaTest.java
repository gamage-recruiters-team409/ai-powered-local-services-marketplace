package com.gamagerecruiters.marketplace.modules.provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;
import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;
import com.gamagerecruiters.marketplace.modules.provider.dto.CreateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.filestorage.ProfileImageStorageService;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationReverificationService;

@ExtendWith(MockitoExtension.class)
class ProviderServiceAreaTest {

    private static final String USER_ID = "provider-user-1";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private CategoryService categoryService;

    @Mock
    private DistrictCatalog districtCatalog;

    @Mock
    private ProviderVerificationReverificationService
            providerVerificationReverificationService;

    @Mock
    private ProfileImageStorageService profileImageStorageService;

    @InjectMocks
    private ProviderProfileServiceImpl providerProfileService;

    @BeforeEach
    void setUp() {
        UserPrincipal principal = new UserPrincipal(
                USER_ID,
                "provider@test.com",
                Role.PROVIDER
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                )
        );

        lenient().when(districtCatalog.isValidDistrict(anyString()))
                .thenReturn(true);

        lenient().when(districtCatalog.getAllDistricts())
                .thenReturn(new LinkedHashSet<>(List.of(
                        "Colombo",
                        "Gampaha",
                        "Galle"
                )));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createProfile_shouldSaveMultipleSelectedServiceAreaDistricts() {
        CreateProviderProfileRequest request = createRequest();
        request.setServiceAreaDistricts(List.of("Gampaha", "Galle"));

        when(providerProfileRepository.existsByUserId(USER_ID))
                .thenReturn(false);
        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> {
                    ProviderProfile profile = invocation.getArgument(0);
                    profile.setId("profile-1");
                    return profile;
                });

        ProviderProfileResponse response =
                providerProfileService.createMyProfile(request);

        assertEquals(
                List.of("Gampaha", "Galle"),
                response.getServiceAreaDistricts()
        );
    }

    @Test
    void createProfile_shouldUseExistingDistrictWhenLegacyClientOmitsServiceAreas() {
        CreateProviderProfileRequest request = createRequest();

        when(providerProfileRepository.existsByUserId(USER_ID))
                .thenReturn(false);
        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProviderProfileResponse response =
                providerProfileService.createMyProfile(request);

        assertEquals(
                List.of("Colombo"),
                response.getServiceAreaDistricts()
        );
    }

    @Test
    void createProfile_shouldRejectUnknownServiceAreaDistrict() {
        CreateProviderProfileRequest request = createRequest();
        request.setServiceAreaDistricts(List.of("Atlantis"));

        when(providerProfileRepository.existsByUserId(USER_ID))
                .thenReturn(false);
        when(districtCatalog.isValidDistrict("Atlantis"))
                .thenReturn(false);

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> providerProfileService.createMyProfile(request)
        );

        assertEquals(
                "INVALID_SERVICE_AREA_DISTRICT",
                exception.getErrorCode()
        );
        verify(providerProfileRepository, never())
                .save(any(ProviderProfile.class));
    }

    @Test
    void createProfile_shouldRejectDuplicateDistrictsIgnoringCase() {
        CreateProviderProfileRequest request = createRequest();
        request.setServiceAreaDistricts(List.of("Galle", "galle"));

        when(providerProfileRepository.existsByUserId(USER_ID))
                .thenReturn(false);

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> providerProfileService.createMyProfile(request)
        );

        assertEquals(
                "PROVIDER_SERVICE_AREA_DUPLICATE",
                exception.getErrorCode()
        );
        verify(providerProfileRepository, never())
                .save(any(ProviderProfile.class));
    }

    @Test
    void createProfile_shouldRejectEmptyExplicitServiceAreaSelection() {
        CreateProviderProfileRequest request = createRequest();
        request.setServiceAreaDistricts(List.of());

        when(providerProfileRepository.existsByUserId(USER_ID))
                .thenReturn(false);

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> providerProfileService.createMyProfile(request)
        );

        assertEquals(
                "PROVIDER_SERVICE_AREA_REQUIRED",
                exception.getErrorCode()
        );
        verify(providerProfileRepository, never())
                .save(any(ProviderProfile.class));
    }

    @Test
    void updateProfile_shouldReplaceServiceAreaDistrictsWhenProvided() {
        ProviderProfile profile = existingProfile();
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));
        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();
        request.setServiceAreaDistricts(List.of("Gampaha", "Galle"));

        ProviderProfileResponse response =
                providerProfileService.updateMyProfile(request);

        assertEquals(
                List.of("Gampaha", "Galle"),
                response.getServiceAreaDistricts()
        );
    }

    @Test
    void updateProfile_shouldPreserveServiceAreasWhenFieldIsOmitted() {
        ProviderProfile profile = existingProfile();
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));
        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();
        request.setBio("Updated bio");

        ProviderProfileResponse response =
                providerProfileService.updateMyProfile(request);

        assertEquals(
                List.of("Colombo", "Gampaha"),
                response.getServiceAreaDistricts()
        );
    }

    @Test
    void getProfile_shouldReturnDistrictAsFallbackForLegacyProfile() {
        ProviderProfile profile = existingProfile();
        profile.setServiceAreaDistricts(null);
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        ProviderProfileResponse response =
                providerProfileService.getMyProfile();

        assertEquals(
                List.of("Colombo"),
                response.getServiceAreaDistricts()
        );
        assertTrue(response.getServiceAreaDistricts().contains("Colombo"));
    }

    private CreateProviderProfileRequest createRequest() {
        CreateProviderProfileRequest request =
                new CreateProviderProfileRequest();
        request.setProviderType(ProviderType.INDIVIDUAL);
        request.setDisplayName("Provider One");
        request.setDistrict("Colombo");
        request.setCity("Colombo");
        return request;
    }

    private ProviderProfile existingProfile() {
        ProviderProfile profile = new ProviderProfile();
        profile.setId("profile-1");
        profile.setUserId(USER_ID);
        profile.setProviderType(ProviderType.INDIVIDUAL);
        profile.setDisplayName("Provider One");
        profile.setDistrict("Colombo");
        profile.setCity("Colombo");
        profile.setServiceAreaDistricts(List.of("Colombo", "Gampaha"));
        return profile;
    }
}

package com.gamagerecruiters.marketplace.modules.provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;
import com.gamagerecruiters.marketplace.modules.provider.dto.CreateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

@ExtendWith(MockitoExtension.class)
class ProviderProfileServiceImplTest {

    private static final String USER_ID = "provider-user-1";
    private static final String EMAIL = "provider@test.com";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @InjectMocks
    private ProviderProfileServiceImpl providerProfileService;

    @BeforeEach
    void setUpSecurityContext() {
        UserPrincipal principal = new UserPrincipal(
                USER_ID,
                EMAIL,
                Role.PROVIDER
        );

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createMyProfile_shouldCreateProfileForCurrentProvider() {
        CreateProviderProfileRequest request = new CreateProviderProfileRequest();
        request.setProviderType(ProviderType.INDIVIDUAL);
        request.setDisplayName("Hiba Services");
        request.setBio("Professional local service provider.");
        request.setDistrict("Colombo");
        request.setCity("Colombo");
        request.setExperienceYears(2);
        request.setProfileImageUrl("https://example.com/profile.jpg");

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

        assertNotNull(response);
        assertEquals("profile-1", response.getId());
        assertEquals(USER_ID, response.getUserId());
        assertEquals(ProviderType.INDIVIDUAL, response.getProviderType());
        assertEquals("Hiba Services", response.getDisplayName());
        assertEquals("Professional local service provider.", response.getBio());
        assertEquals("Colombo", response.getDistrict());
        assertEquals("Colombo", response.getCity());
        assertEquals(2, response.getExperienceYears());

        verify(providerProfileRepository).save(any(ProviderProfile.class));
    }

    @Test
    void createMyProfile_shouldRejectDuplicateProfile() {
        CreateProviderProfileRequest request = new CreateProviderProfileRequest();
        request.setProviderType(ProviderType.INDIVIDUAL);
        request.setDisplayName("Hiba Services");
        request.setDistrict("Colombo");
        request.setCity("Colombo");

        when(providerProfileRepository.existsByUserId(USER_ID))
                .thenReturn(true);

        assertThrows(
                ResourceConflictException.class,
                () -> providerProfileService.createMyProfile(request)
        );

        verify(providerProfileRepository, never())
                .save(any(ProviderProfile.class));
    }

    @Test
    void getMyProfile_shouldReturnCurrentProvidersProfile() {
        ProviderProfile profile = createExistingProfile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(profile));

        ProviderProfileResponse response =
                providerProfileService.getMyProfile();

        assertEquals("profile-1", response.getId());
        assertEquals(USER_ID, response.getUserId());
        assertEquals("Hiba Services", response.getDisplayName());
    }

    @Test
    void getMyProfile_shouldThrowWhenProfileDoesNotExist() {
        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> providerProfileService.getMyProfile()
        );
    }

    @Test
    void updateMyProfile_shouldUpdateOnlyProvidedFields() {
        ProviderProfile existingProfile = createExistingProfile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(existingProfile));

        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();

        request.setBio("Updated provider profile bio.");
        request.setExperienceYears(3);

        ProviderProfileResponse response =
                providerProfileService.updateMyProfile(request);

        assertEquals("Updated provider profile bio.", response.getBio());
        assertEquals(3, response.getExperienceYears());

        // Existing values should remain unchanged.
        assertEquals("Hiba Services", response.getDisplayName());
        assertEquals("Colombo", response.getDistrict());
        assertEquals("Colombo", response.getCity());

        assertNotNull(response.getUpdatedAt());

        verify(providerProfileRepository)
                .save(existingProfile);
    }

    private ProviderProfile createExistingProfile() {
        ProviderProfile profile = new ProviderProfile();

        profile.setId("profile-1");
        profile.setUserId(USER_ID);
        profile.setProviderType(ProviderType.INDIVIDUAL);
        profile.setDisplayName("Hiba Services");
        profile.setBio("Professional local service provider.");
        profile.setDistrict("Colombo");
        profile.setCity("Colombo");
        profile.setExperienceYears(2);
        profile.setProfileImageUrl("https://example.com/profile.jpg");
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());

        return profile;
    }
}
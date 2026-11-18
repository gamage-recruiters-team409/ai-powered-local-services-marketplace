package com.gamagerecruiters.marketplace.modules.provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.domain.Role;
import com.gamagerecruiters.marketplace.modules.auth.security.UserPrincipal;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderType;
import com.gamagerecruiters.marketplace.modules.provider.dto.CreateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationReverificationService;

@ExtendWith(MockitoExtension.class)
class ProviderProfileServiceImplTest {

    private static final String USER_ID = "provider-user-1";
    private static final String EMAIL = "provider@test.com";

    @Mock
    private ProviderProfileRepository providerProfileRepository;

    @Mock
    private CategoryService categoryService;

    @Mock
    private ProviderVerificationReverificationService
            providerVerificationReverificationService;

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

        CreateProviderProfileRequest request = createProfileRequest();

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
        assertEquals(List.of(), response.getCategoryIds());

        verify(providerProfileRepository)
                .save(any(ProviderProfile.class));

        // Creating a profile must not trigger re-verification.
        verify(providerVerificationReverificationService, never())
                .requestCategoryReverification("profile-1");
    }

    @Test
    void createMyProfile_shouldRejectDuplicateProfileDetectedByPreCheck() {

        CreateProviderProfileRequest request = createProfileRequest();

        when(providerProfileRepository.existsByUserId(USER_ID))
                .thenReturn(true);

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> providerProfileService.createMyProfile(request)
        );

        assertEquals(
                "PROVIDER_PROFILE_ALREADY_EXISTS",
                exception.getErrorCode()
        );

        verify(providerProfileRepository, never())
                .save(any(ProviderProfile.class));
    }

    @Test
    void createMyProfile_shouldReturnConflictWhenMongoUniqueIndexRejectsConcurrentDuplicate() {

        CreateProviderProfileRequest request = createProfileRequest();

        /*
         * Simulates two concurrent requests attempting to create
         * a profile for the same authenticated provider.
         */
        when(providerProfileRepository.existsByUserId(USER_ID))
                .thenReturn(false);

        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenThrow(
                        new DuplicateKeyException(
                                "E11000 duplicate key error: userId_1"
                        )
                );

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> providerProfileService.createMyProfile(request)
        );

        assertEquals(
                "PROVIDER_PROFILE_ALREADY_EXISTS",
                exception.getErrorCode()
        );

        verify(providerProfileRepository)
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

        assertEquals(
                "Updated provider profile bio.",
                response.getBio()
        );

        assertEquals(3, response.getExperienceYears());

        // Existing fields must remain unchanged.
        assertEquals("Hiba Services", response.getDisplayName());
        assertEquals("Colombo", response.getDistrict());
        assertEquals("Colombo", response.getCity());

        // Existing categories must also remain unchanged.
        assertEquals(
                List.of("existing-category"),
                response.getCategoryIds()
        );

        assertNotNull(response.getUpdatedAt());

        verify(providerProfileRepository)
                .save(existingProfile);

        // Non-category profile updates must not trigger re-verification.
        verify(providerVerificationReverificationService, never())
                .requestCategoryReverification("profile-1");
    }

    // CATEGORY TESTS

    @Test
    void createMyProfile_shouldSaveSelectedCategories() {

        CreateProviderProfileRequest request = createProfileRequest();

        request.setCategoryIds(
                List.of("category-1", "category-2")
        );

        when(providerProfileRepository.existsByUserId(USER_ID))
                .thenReturn(false);

        when(categoryService.getCategoryById("category-1"))
                .thenReturn(activeCategory("category-1"));

        when(categoryService.getCategoryById("category-2"))
                .thenReturn(activeCategory("category-2"));

        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> {
                    ProviderProfile profile = invocation.getArgument(0);
                    profile.setId("profile-1");
                    return profile;
                });

        ProviderProfileResponse response =
                providerProfileService.createMyProfile(request);

        assertEquals(
                List.of("category-1", "category-2"),
                response.getCategoryIds()
        );

        verify(categoryService).getCategoryById("category-1");
        verify(categoryService).getCategoryById("category-2");

        verify(providerVerificationReverificationService, never())
                .requestCategoryReverification("profile-1");
    }

    @Test
    void createMyProfile_shouldRejectInactiveCategory() {

        CreateProviderProfileRequest request = createProfileRequest();

        request.setCategoryIds(
                List.of("inactive-category")
        );

        when(providerProfileRepository.existsByUserId(USER_ID))
                .thenReturn(false);

        when(categoryService.getCategoryById("inactive-category"))
                .thenReturn(category("inactive-category", false));

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> providerProfileService.createMyProfile(request)
        );

        assertEquals(
                "PROVIDER_CATEGORY_INACTIVE",
                exception.getErrorCode()
        );

        verify(providerProfileRepository, never())
                .save(any(ProviderProfile.class));
    }

    @Test
    void createMyProfile_shouldRejectDuplicateCategories() {

        CreateProviderProfileRequest request = createProfileRequest();

        request.setCategoryIds(
                List.of("category-1", "category-1")
        );

        when(providerProfileRepository.existsByUserId(USER_ID))
                .thenReturn(false);

        when(categoryService.getCategoryById("category-1"))
                .thenReturn(activeCategory("category-1"));

        ResourceConflictException exception = assertThrows(
                ResourceConflictException.class,
                () -> providerProfileService.createMyProfile(request)
        );

        assertEquals(
                "PROVIDER_CATEGORY_DUPLICATE",
                exception.getErrorCode()
        );

        verify(providerProfileRepository, never())
                .save(any(ProviderProfile.class));
    }

    @Test
    void updateMyProfile_shouldUpdateSelectedCategories() {

        ProviderProfile existingProfile = createExistingProfile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(existingProfile));

        when(categoryService.getCategoryById("category-3"))
                .thenReturn(activeCategory("category-3"));

        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();

        request.setCategoryIds(
                List.of("category-3")
        );

        ProviderProfileResponse response =
                providerProfileService.updateMyProfile(request);

        assertEquals(
                List.of("category-3"),
                response.getCategoryIds()
        );

        verify(categoryService)
                .getCategoryById("category-3");

        verify(providerProfileRepository)
                .save(existingProfile);

        // The new category must request re-verification.
        verify(providerVerificationReverificationService)
                .requestCategoryReverification("profile-1");
    }

    @Test
    void updateMyProfile_shouldRequestReverificationWhenNewCategoryIsAdded() {

        ProviderProfile existingProfile = createExistingProfile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(existingProfile));

        when(categoryService.getCategoryById("existing-category"))
                .thenReturn(activeCategory("existing-category"));

        when(categoryService.getCategoryById("category-2"))
                .thenReturn(activeCategory("category-2"));

        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();

        request.setCategoryIds(
                List.of("existing-category", "category-2")
        );

        ProviderProfileResponse response =
                providerProfileService.updateMyProfile(request);

        assertEquals(
                List.of("existing-category", "category-2"),
                response.getCategoryIds()
        );

        verify(providerVerificationReverificationService)
                .requestCategoryReverification("profile-1");
    }

    @Test
    void updateMyProfile_shouldNotRequestReverificationWhenCategoriesAreUnchanged() {

        ProviderProfile existingProfile = createExistingProfile();

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(existingProfile));

        when(categoryService.getCategoryById("existing-category"))
                .thenReturn(activeCategory("existing-category"));

        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();

        request.setCategoryIds(
                List.of("existing-category")
        );

        ProviderProfileResponse response =
                providerProfileService.updateMyProfile(request);

        assertEquals(
                List.of("existing-category"),
                response.getCategoryIds()
        );

        verify(providerVerificationReverificationService, never())
                .requestCategoryReverification("profile-1");
    }

    @Test
    void updateMyProfile_shouldNotRequestReverificationWhenCategoryIsRemoved() {

        ProviderProfile existingProfile = createExistingProfile();

        existingProfile.setCategoryIds(
                List.of("existing-category", "category-2")
        );

        when(providerProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(existingProfile));

        when(categoryService.getCategoryById("existing-category"))
                .thenReturn(activeCategory("existing-category"));

        when(providerProfileRepository.save(any(ProviderProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProviderProfileRequest request =
                new UpdateProviderProfileRequest();

        request.setCategoryIds(
                List.of("existing-category")
        );

        ProviderProfileResponse response =
                providerProfileService.updateMyProfile(request);

        assertEquals(
                List.of("existing-category"),
                response.getCategoryIds()
        );

        verify(providerVerificationReverificationService, never())
                .requestCategoryReverification("profile-1");
    }

    private CreateProviderProfileRequest createProfileRequest() {

        CreateProviderProfileRequest request =
                new CreateProviderProfileRequest();

        request.setProviderType(ProviderType.INDIVIDUAL);
        request.setDisplayName("Hiba Services");
        request.setBio("Professional local service provider.");
        request.setDistrict("Colombo");
        request.setCity("Colombo");
        request.setExperienceYears(2);
        request.setProfileImageUrl(
                "https://example.com/profile.jpg"
        );

        return request;
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
        profile.setProfileImageUrl(
                "https://example.com/profile.jpg"
        );

        profile.setCategoryIds(
                List.of("existing-category")
        );

        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());

        return profile;
    }

    private CategoryResponse activeCategory(String id) {
        return category(id, true);
    }

    private CategoryResponse category(
            String id,
            boolean active
    ) {

        return new CategoryResponse(
                id,
                "Test Category",
                "test-category-" + id,
                null,
                null,
                null,
                null,
                active,
                null,
                null
        );
    }
}
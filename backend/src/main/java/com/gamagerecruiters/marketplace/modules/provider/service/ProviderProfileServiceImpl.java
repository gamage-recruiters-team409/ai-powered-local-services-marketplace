
package com.gamagerecruiters.marketplace.modules.provider.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.dto.CreateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;

@Service
public class ProviderProfileServiceImpl implements ProviderProfileService {

    private final ProviderProfileRepository providerProfileRepository;
    private final CategoryService categoryService;

    public ProviderProfileServiceImpl(
            ProviderProfileRepository providerProfileRepository,
            CategoryService categoryService
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.categoryService = categoryService;
    }

    @Override
    public ProviderProfileResponse createMyProfile(
            CreateProviderProfileRequest request
    ) {
        String userId = requireCurrentUserId();

        if (providerProfileRepository.existsByUserId(userId)) {
            throw providerProfileAlreadyExists();
        }

        ProviderProfile profile = new ProviderProfile();

        profile.setUserId(userId);
        profile.setProviderType(request.getProviderType());
        profile.setDisplayName(request.getDisplayName().trim());
        profile.setBio(trimToNull(request.getBio()));
        profile.setDistrict(request.getDistrict().trim());
        profile.setCity(request.getCity().trim());
        profile.setExperienceYears(request.getExperienceYears());
        profile.setProfileImageUrl(
                trimToNull(request.getProfileImageUrl())
        );

        // Validate selected categories before saving.
        profile.setCategoryIds(
                validateCategoryIds(request.getCategoryIds())
        );

        Instant now = Instant.now();

        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);

        try {
            return toResponse(
                    providerProfileRepository.save(profile)
            );
        } catch (DuplicateKeyException ex) {
            /*
             * The pre-check provides a friendly duplicate response.
             * The MongoDB unique index protects against concurrent requests.
             */
            throw providerProfileAlreadyExists();
        }
    }

    @Override
    public ProviderProfileResponse getMyProfile() {

        String userId = requireCurrentUserId();

        ProviderProfile profile = providerProfileRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));

        return toResponse(profile);
    }

    @Override
    public ProviderProfileResponse updateMyProfile(
            UpdateProviderProfileRequest request
    ) {

        String userId = requireCurrentUserId();

        ProviderProfile profile = providerProfileRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));

        if (request.getProviderType() != null) {
            profile.setProviderType(request.getProviderType());
        }

        if (request.getDisplayName() != null) {
            profile.setDisplayName(request.getDisplayName().trim());
        }

        if (request.getBio() != null) {
            profile.setBio(trimToNull(request.getBio()));
        }

        if (request.getDistrict() != null) {
            profile.setDistrict(request.getDistrict().trim());
        }

        if (request.getCity() != null) {
            profile.setCity(request.getCity().trim());
        }

        if (request.getExperienceYears() != null) {
            profile.setExperienceYears(request.getExperienceYears());
        }

        if (request.getProfileImageUrl() != null) {
            profile.setProfileImageUrl(
                    trimToNull(request.getProfileImageUrl())
            );
        }

        // Preserve existing categories if categoryIds is omitted.
        if (request.getCategoryIds() != null) {
            profile.setCategoryIds(
                    validateCategoryIds(request.getCategoryIds())
            );
        }

        profile.setUpdatedAt(Instant.now());

        return toResponse(
                providerProfileRepository.save(profile)
        );
    }

    /**
     * Validates selected category IDs using the existing Catalog module.
     *
     * Only existing and active categories can be selected.
     * Duplicate IDs and blank IDs are rejected.
     */
    private List<String> validateCategoryIds(
            List<String> categoryIds
    ) {

        if (categoryIds == null || categoryIds.isEmpty()) {
            return List.of();
        }

        Set<String> uniqueCategoryIds = new LinkedHashSet<>();

        for (String rawId : categoryIds) {

            if (rawId == null || rawId.isBlank()) {
                throw new ResourceConflictException(
                        "PROVIDER_CATEGORY_INVALID",
                        "Category ID cannot be empty"
                );
            }

            String categoryId = rawId.trim();

            if (!uniqueCategoryIds.add(categoryId)) {
                throw new ResourceConflictException(
                        "PROVIDER_CATEGORY_DUPLICATE",
                        "Duplicate category selection: " + categoryId
                );
            }

            // Find category using the existing Catalog service.
            CategoryResponse category =
                    categoryService.getCategoryById(categoryId);

            // Prevent selection of deactivated categories.
            if (!category.active()) {
                throw new ResourceConflictException(
                        "PROVIDER_CATEGORY_INACTIVE",
                        "Selected category is inactive: " + categoryId
                );
            }
        }

        return new ArrayList<>(uniqueCategoryIds);
    }

    private ResourceConflictException providerProfileAlreadyExists() {

        return new ResourceConflictException(
                "PROVIDER_PROFILE_ALREADY_EXISTS",
                "A provider profile already exists for this user"
        );
    }

    private String requireCurrentUserId() {

        String userId = CurrentUser.getUserId();

        if (userId == null) {
            throw new AccessDeniedException(
                    "Authentication required"
            );
        }

        return userId;
    }

    private String trimToNull(String value) {

        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    private ProviderProfileResponse toResponse(
            ProviderProfile profile
    ) {

        ProviderProfileResponse response =
                new ProviderProfileResponse(
                        profile.getId(),
                        profile.getUserId(),
                        profile.getProviderType(),
                        profile.getDisplayName(),
                        profile.getBio(),
                        profile.getDistrict(),
                        profile.getCity(),
                        profile.getExperienceYears(),
                        profile.getProfileImageUrl(),
                        profile.getCreatedAt(),
                        profile.getUpdatedAt()
                );

        response.setCategoryIds(profile.getCategoryIds());

        return response;
    }
}

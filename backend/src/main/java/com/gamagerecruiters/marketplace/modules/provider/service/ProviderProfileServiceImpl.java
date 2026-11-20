
package com.gamagerecruiters.marketplace.modules.provider.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.gamagerecruiters.marketplace.common.exception.ResourceConflictException;
import com.gamagerecruiters.marketplace.common.exception.ResourceNotFoundException;
import com.gamagerecruiters.marketplace.modules.auth.security.CurrentUser;
import com.gamagerecruiters.marketplace.modules.catalog.dto.CategoryResponse;
import com.gamagerecruiters.marketplace.modules.catalog.service.CategoryService;
import com.gamagerecruiters.marketplace.modules.customer.service.DistrictCatalog;
import com.gamagerecruiters.marketplace.modules.provider.domain.ProviderProfile;
import com.gamagerecruiters.marketplace.modules.provider.dto.CreateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.dto.ProviderProfileResponse;
import com.gamagerecruiters.marketplace.modules.provider.dto.UpdateProviderProfileRequest;
import com.gamagerecruiters.marketplace.modules.provider.filestorage.ProfileImageStorageService;
import com.gamagerecruiters.marketplace.modules.provider.filestorage.StoredProfileImage;
import com.gamagerecruiters.marketplace.modules.provider.repository.ProviderProfileRepository;
import com.gamagerecruiters.marketplace.modules.verification.service.ProviderVerificationReverificationService;

@Service
public class ProviderProfileServiceImpl implements ProviderProfileService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProviderProfileServiceImpl.class);

    private final ProviderProfileRepository providerProfileRepository;
    private final CategoryService categoryService;
    private final DistrictCatalog districtCatalog;
    private final ProviderVerificationReverificationService
            providerVerificationReverificationService;
    private final ProfileImageStorageService profileImageStorageService;

    public ProviderProfileServiceImpl(
            ProviderProfileRepository providerProfileRepository,
            CategoryService categoryService,
            DistrictCatalog districtCatalog,
            ProviderVerificationReverificationService
                    providerVerificationReverificationService,
            ProfileImageStorageService profileImageStorageService
    ) {
        this.providerProfileRepository = providerProfileRepository;
        this.categoryService = categoryService;
        this.districtCatalog = districtCatalog;
        this.providerVerificationReverificationService =
                providerVerificationReverificationService;
        this.profileImageStorageService = profileImageStorageService;
    }

    /**
     * Returns the configured service-area districts for a provider.
     *
     * @param providerId the provider's user ID (not the profile document ID)
     * @return an immutable set of configured district names, or an empty set
     *         when the provider ID, profile, or service-area selection is missing
     */
    @Override
    public Set<String> getServiceAreaDistricts(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Set.of();
        }

        ProviderProfile profile = providerProfileRepository
                .findByUserId(providerId.trim())
                .orElse(null);

        if (profile == null) {
            return Set.of();
        }

        List<String> configuredDistricts = profile.getServiceAreaDistricts();

        if (configuredDistricts == null || configuredDistricts.isEmpty()) {
            return Set.of();
        }

        // Return a defensive, immutable set and remove any duplicate values.
        return Set.copyOf(new LinkedHashSet<>(configuredDistricts));
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
        profile.setDistrict(validateDistrict(request.getDistrict()));
        profile.setCity(request.getCity().trim());
        profile.setServiceAreaDistricts(
                resolveServiceAreaDistricts(
                        request.getServiceAreaDistricts(),
                        profile.getDistrict()
                )
        );
        profile.setExperienceYears(request.getExperienceYears());

        // Preserve existing profile image URL functionality.
        profile.setProfileImageUrl(
                trimToNull(request.getProfileImageUrl())
        );

        // Existing category validation is preserved.
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
    @Transactional
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
            profile.setDistrict(validateDistrict(request.getDistrict()));
        }

        if (request.getCity() != null) {
            profile.setCity(request.getCity().trim());
        }

        if (request.getServiceAreaDistricts() != null) {
            profile.setServiceAreaDistricts(
                    validateServiceAreaDistricts(request.getServiceAreaDistricts())
            );
        }

        if (request.getExperienceYears() != null) {
            profile.setExperienceYears(request.getExperienceYears());
        }

        if (request.getProfileImageUrl() != null) {
            profile.setProfileImageUrl(
                    trimToNull(request.getProfileImageUrl())
            );
        }

        /*
         * Preserve existing categories if categoryIds is omitted.
         *
         * Re-verification is requested only when a new category
         * is added to the existing category selection.
         */
        if (request.getCategoryIds() != null) {

            List<String> validatedCategoryIds =
                    validateCategoryIds(request.getCategoryIds());

            boolean newCategoryAdded = hasNewCategory(
                    profile.getCategoryIds(),
                    validatedCategoryIds
            );

            if (newCategoryAdded) {

                /*
                 * Coordinate category additions with the verification lifecycle:
                 *
                 * - APPROVED providers move to RESUBMISSION_REQUESTED.
                 * - SUBMITTED and UNDER_REVIEW providers receive a conflict.
                 * - Other verification statuses retain their existing workflow.
                 *
                 * The verification service must complete this check before
                 * the updated category selection is saved.
                 */
                providerVerificationReverificationService
                        .requestCategoryReverification(profile.getId());
            }

            profile.setCategoryIds(validatedCategoryIds);
        }

        profile.setUpdatedAt(Instant.now());

        return toResponse(
                providerProfileRepository.save(profile)
        );
    }

    /**
     * Uploads or replaces the authenticated provider's profile image.
     *
     * The new image is uploaded before the database is updated.
     * The previous Cloudinary image is deleted only after the new
     * image reference has been successfully saved.
     */
    @Override
    public ProviderProfileResponse uploadMyProfileImage(
            MultipartFile file
    ) {

        String userId = requireCurrentUserId();

        ProviderProfile profile = providerProfileRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));

        // Upload the new image to Cloudinary first.
        StoredProfileImage uploadedImage =
                profileImageStorageService.upload(
                        profile.getId(),
                        file
                );

        String previousPublicId =
                profile.getProfileImagePublicId();

        profile.setProfileImageUrl(
                uploadedImage.secureUrl()
        );

        profile.setProfileImagePublicId(
                uploadedImage.publicId()
        );

        profile.setUpdatedAt(Instant.now());

        ProviderProfile savedProfile;

        try {

            // Persist the new Cloudinary references.
            savedProfile = providerProfileRepository.save(profile);

        } catch (RuntimeException exception) {

            /*
             * If database persistence fails, attempt to remove
             * the newly uploaded image.
             *
             * The previous profile image remains unchanged in the database.
             */
            deleteProfileImageQuietly(
                    uploadedImage.publicId(),
                    "cleaning up an unsuccessful upload"
            );

            throw exception;
        }

        /*
         * Delete the previous image only after the new image
         * has been successfully saved.
         */
        if (hasText(previousPublicId)
                && !previousPublicId.equals(uploadedImage.publicId())) {

            deleteProfileImageQuietly(
                    previousPublicId,
                    "replacing the previous profile image"
            );
        }

        return toResponse(savedProfile);
    }

    /**
     * Removes the authenticated provider's profile image.
     *
     * The database reference is cleared before the old Cloudinary
     * asset is deleted, avoiding a profile that references an
     * already-deleted image if database persistence fails.
     */
    @Override
    public ProviderProfileResponse removeMyProfileImage() {

        String userId = requireCurrentUserId();

        ProviderProfile profile = providerProfileRepository
                .findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PROVIDER_PROFILE_NOT_FOUND",
                        "Provider profile not found"
                ));

        String previousPublicId =
                profile.getProfileImagePublicId();

        if (!hasText(profile.getProfileImageUrl())
                && !hasText(previousPublicId)) {

            return toResponse(profile);
        }

        // Clear both image references.
        profile.setProfileImageUrl(null);
        profile.setProfileImagePublicId(null);
        profile.setUpdatedAt(Instant.now());

        ProviderProfile savedProfile =
                providerProfileRepository.save(profile);

        // Remove the old Cloudinary asset after persistence.
        if (hasText(previousPublicId)) {

            deleteProfileImageQuietly(
                    previousPublicId,
                    "removing the provider profile image"
            );
        }

        return toResponse(savedProfile);
    }

    /**
     * Attempts to delete an image without allowing a Cloudinary
     * cleanup failure to undo an already-persisted profile update.
     *
     * A cleanup failure is logged for investigation.
     */
    private void deleteProfileImageQuietly(
            String publicId,
            String operation
    ) {

        if (!hasText(publicId)) {
            return;
        }

        try {

            profileImageStorageService.delete(publicId);

        } catch (RuntimeException exception) {

            LOGGER.warn(
                    "Unable to delete provider profile image while {}. "
                            + "Cloudinary cleanup may be required.",
                    operation,
                    exception
            );
        }
    }

    /**
     * Checks whether the updated selection contains a category
     * that was not present in the existing selection.
     *
     * Category removal and category ordering changes do not
     * count as adding a new category.
     */
    private boolean hasNewCategory(
            List<String> existingCategoryIds,
            List<String> updatedCategoryIds
    ) {

        Set<String> existingCategories =
                existingCategoryIds == null
                        ? Set.of()
                        : new LinkedHashSet<>(existingCategoryIds);

        return updatedCategoryIds.stream()
                .anyMatch(categoryId ->
                        !existingCategories.contains(categoryId)
                );
    }

    /**
     * Resolves service-area districts for profile creation.
     *
     * Existing clients that do not yet send serviceAreaDistricts remain compatible:
     * the already validated profile district becomes the initial service area.
     */
    private List<String> resolveServiceAreaDistricts(
            List<String> requestedDistricts,
            String fallbackDistrict
    ) {
        if (requestedDistricts == null) {
            return List.of(validateDistrict(fallbackDistrict));
        }

        return validateServiceAreaDistricts(requestedDistricts);
    }

    /**
     * Validates and normalizes the selected service-area districts against the
     * same fixed Sri Lankan district catalog used by the Customer module.
     */
    private List<String> validateServiceAreaDistricts(
            List<String> requestedDistricts
    ) {
        if (requestedDistricts == null || requestedDistricts.isEmpty()) {
            throw new ResourceConflictException(
                    "PROVIDER_SERVICE_AREA_REQUIRED",
                    "Select at least one service-area district"
            );
        }

        if (requestedDistricts.size() > 25) {
            throw new ResourceConflictException(
                    "PROVIDER_SERVICE_AREA_LIMIT_EXCEEDED",
                    "A maximum of 25 service-area districts can be selected"
            );
        }

        List<String> validatedDistricts = new ArrayList<>();

        for (String rawDistrict : requestedDistricts) {
            if (rawDistrict == null || rawDistrict.isBlank()) {
                throw new ResourceConflictException(
                        "PROVIDER_SERVICE_AREA_INVALID",
                        "Service-area district cannot be empty"
                );
            }

            String normalizedDistrict = rawDistrict.trim();

            if (!districtCatalog.isValidDistrict(normalizedDistrict)) {
                throw new ResourceConflictException(
                        "INVALID_SERVICE_AREA_DISTRICT",
                        "Service-area district '" + normalizedDistrict
                                + "' is not a valid Sri Lankan district"
                );
            }

            String canonicalDistrict = districtCatalog.getAllDistricts().stream()
                    .filter(knownDistrict ->
                            knownDistrict.equalsIgnoreCase(normalizedDistrict)
                    )
                    .findFirst()
                    .orElse(normalizedDistrict);

            boolean duplicate = validatedDistricts.stream()
                    .anyMatch(existingDistrict ->
                            existingDistrict.equalsIgnoreCase(canonicalDistrict)
                    );

            if (duplicate) {
                throw new ResourceConflictException(
                        "PROVIDER_SERVICE_AREA_DUPLICATE",
                        "Duplicate service-area district: " + canonicalDistrict
                );
            }

            validatedDistricts.add(canonicalDistrict);
        }

        return List.copyOf(validatedDistricts);
    }

    /**
     * Validates the selected district against the shared Sri Lankan district catalog.
     * The existing city field remains unchanged because the shared catalog currently
     * provides districts, not a separate city catalog.
     */
    private String validateDistrict(String district) {

        String normalizedDistrict =
                district == null ? null : district.trim();

        if (normalizedDistrict == null
                || normalizedDistrict.isBlank()
                || !districtCatalog.isValidDistrict(normalizedDistrict)) {

            throw new ResourceConflictException(
                    "INVALID_DISTRICT",
                    "District '" + normalizedDistrict
                            + "' is not a valid Sri Lankan district"
            );
        }

        return normalizedDistrict;
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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

        List<String> serviceAreaDistricts = profile.getServiceAreaDistricts();
        if (serviceAreaDistricts.isEmpty() && hasText(profile.getDistrict())) {
            // Backward-compatible response for profiles created before this field existed.
            serviceAreaDistricts = List.of(profile.getDistrict());
        }
        response.setServiceAreaDistricts(serviceAreaDistricts);

        return response;
    }
}
